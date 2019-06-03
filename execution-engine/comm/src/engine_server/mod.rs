use std::collections::HashMap;
use std::convert::TryInto;
use std::fmt::Debug;
use std::io::ErrorKind;
use std::marker::{Send, Sync};

use common::key::Key;
use execution_engine::engine_state::error::Error as EngineError;
use execution_engine::engine_state::execution_result::ExecutionResult;
use execution_engine::engine_state::EngineState;
use execution_engine::execution::{Executor, WasmiExecutor};
use execution_engine::tracking_copy::QueryResult;
use ipc::*;
use ipc_grpc::ExecutionEngineService;
use mappings::*;
use shared::newtypes::Blake2bHash;
use shared::transform::Transform;
use storage::global_state::History;
use wasm_prep::wasm_costs::WasmCosts;
use wasm_prep::{Preprocessor, WasmiPreprocessor};

use shared::logging;

pub mod ipc;
pub mod ipc_grpc;
pub mod mappings;

// Idea is that Engine will represent the core of the execution engine project.
// It will act as an entry point for execution of Wasm binaries.
// Proto definitions should be translated into domain objects when Engine's API is invoked.
// This way core won't depend on comm (outer layer) leading to cleaner design.
impl<H> ipc_grpc::ExecutionEngineService for EngineState<H>
where
    H: History,
    EngineError: From<H::Error>,
    H::Error: Into<execution_engine::execution::Error> + Debug,
{
    fn query(
        &self,
        _request_options: ::grpc::RequestOptions,
        query_request: ipc::QueryRequest,
    ) -> grpc::SingleResponse<ipc::QueryResponse> {
        // TODO: don't unwrap
        let state_hash: Blake2bHash = query_request.get_state_hash().try_into().unwrap();
        let path = query_request.get_path();
        let mut tracking_copy = match self.tracking_copy(state_hash) {
            Err(storage_error) => {
                let mut result = ipc::QueryResponse::new();
                let error = format!("Error during checkout out Trie: {:?}", storage_error);
                logging::log_error(&error);
                result.set_failure(error);
                return grpc::SingleResponse::completed(result);
            }
            Ok(None) => {
                let mut result = ipc::QueryResponse::new();
                let error = format!("Root not found: {:?}", state_hash);
                logging::log_warning(&error);
                result.set_failure(error);
                return grpc::SingleResponse::completed(result);
            }
            Ok(Some(tracking_copy)) => tracking_copy,
        };
        let key = match query_request.get_base_key().try_into() {
            Err(ParsingError(err_msg)) => {
                logging::log_error(&err_msg);
                let mut result = ipc::QueryResponse::new();
                result.set_failure(err_msg);
                return grpc::SingleResponse::completed(result);
            }
            Ok(key) => key,
        };
        let response = match tracking_copy.query(key, path) {
            Err(err) => {
                let mut result = ipc::QueryResponse::new();
                let error = format!("{:?}", err);
                logging::log_error(&error);
                result.set_failure(error);
                result
            }
            Ok(QueryResult::ValueNotFound(full_path)) => {
                let mut result = ipc::QueryResponse::new();
                let error = format!("Value not found: {:?}", full_path);
                logging::log_warning(&error);
                result.set_failure(error);
                result
            }
            Ok(QueryResult::Success(value)) => {
                let mut result = ipc::QueryResponse::new();
                result.set_success(value.into());
                result
            }
        };
        grpc::SingleResponse::completed(response)
    }

    fn exec(
        &self,
        _o: ::grpc::RequestOptions,
        p: ipc::ExecRequest,
    ) -> grpc::SingleResponse<ipc::ExecResponse> {
        let executor = WasmiExecutor;
        // TODO: don't unwrap
        let prestate_hash: Blake2bHash = p.get_parent_state_hash().try_into().unwrap();
        let deploys = p.get_deploys();
        let protocol_version = p.get_protocol_version();
        // TODO: don't unwrap
        let wasm_costs = WasmCosts::from_version(protocol_version.version).unwrap();
        let preprocessor: WasmiPreprocessor = WasmiPreprocessor::new(wasm_costs);
        let deploys_result: Result<Vec<DeployResult>, RootNotFound> = run_deploys(
            &self,
            &executor,
            &preprocessor,
            prestate_hash,
            deploys,
            protocol_version,
        );
        match deploys_result {
            Ok(deploy_results) => {
                let mut exec_response = ipc::ExecResponse::new();
                let mut exec_result = ipc::ExecResult::new();
                exec_result.set_deploy_results(protobuf::RepeatedField::from_vec(deploy_results));
                exec_response.set_success(exec_result);
                grpc::SingleResponse::completed(exec_response)
            }
            Err(error) => {
                logging::log_error("deploy results error: RootNotFound");
                let mut exec_response = ipc::ExecResponse::new();
                exec_response.set_missing_parent(error);
                grpc::SingleResponse::completed(exec_response)
            }
        }
    }

    fn commit(
        &self,
        _o: ::grpc::RequestOptions,
        p: ipc::CommitRequest,
    ) -> grpc::SingleResponse<ipc::CommitResponse> {
        // TODO: don't unwrap
        let prestate_hash: Blake2bHash = p.get_prestate_hash().try_into().unwrap();
        let effects_result: Result<HashMap<Key, Transform>, ParsingError> =
            p.get_effects().iter().map(TryInto::try_into).collect();
        match effects_result {
            Err(ParsingError(error_message)) => {
                logging::log_error(&error_message);
                let mut res = ipc::CommitResponse::new();
                let mut err = ipc::PostEffectsError::new();
                err.set_message(error_message);
                res.set_failed_transform(err);
                grpc::SingleResponse::completed(res)
            }
            Ok(effects) => {
                let result = grpc_response_from_commit_result::<H>(
                    prestate_hash,
                    self.apply_effect(prestate_hash, effects),
                );
                grpc::SingleResponse::completed(result)
            }
        }
    }

    fn validate(
        &self,
        _o: ::grpc::RequestOptions,
        p: ValidateRequest,
    ) -> grpc::SingleResponse<ValidateResponse> {
        let pay_mod =
            wabt::Module::read_binary(p.payment_code, &wabt::ReadBinaryOptions::default())
                .and_then(|x| x.validate());
        let ses_mod =
            wabt::Module::read_binary(p.session_code, &wabt::ReadBinaryOptions::default())
                .and_then(|x| x.validate());

        match pay_mod.and(ses_mod) {
            Ok(_) => {
                let mut result = ValidateResponse::new();
                result.set_success(ValidateResponse_ValidateSuccess::new());
                grpc::SingleResponse::completed(result)
            }
            Err(cause) => {
                let cause_msg = cause.to_string();
                logging::log_error(&cause_msg);
                let mut result = ValidateResponse::new();
                result.set_failure(cause_msg);
                grpc::SingleResponse::completed(result)
            }
        }
    }
}

fn run_deploys<A, H, E, P>(
    engine_state: &EngineState<H>,
    executor: &E,
    preprocessor: &P,
    prestate_hash: Blake2bHash,
    deploys: &[ipc::Deploy],
    protocol_version: &ProtocolVersion,
) -> Result<Vec<DeployResult>, RootNotFound>
where
    H: History,
    E: Executor<A>,
    P: Preprocessor<A>,
    EngineError: From<H::Error>,
    H::Error: Into<execution_engine::execution::Error>,
{
    // We want to treat RootNotFound error differently b/c it should short-circuit
    // the execution of ALL deploys within the block. This is because all of them share
    // the same prestate and all of them would fail.
    // Iterator (Result<_, _> + collect()) will short circuit the execution
    // when run_deploy returns Err.
    deploys
        .iter()
        .map(|deploy| {
            let session_contract = deploy.get_session();
            let module_bytes = &session_contract.code;
            let args = &session_contract.args;
            let address = match Key::account_from_slice(&deploy.address) {
                Some(key) => key,
                None => {
                    let err = ExecutionResult::failure(EngineError::InvalidAddress, 0);
                    return Ok(err.into());
                }
            };
            let timestamp = deploy.timestamp;
            let nonce = deploy.nonce;
            let gas_limit = deploy.gas_limit as u64;
            engine_state
                .run_deploy(
                    module_bytes,
                    args,
                    address,
                    timestamp,
                    nonce,
                    prestate_hash,
                    gas_limit,
                    protocol_version.get_version(),
                    executor,
                    preprocessor,
                )
                .map(Into::into)
                .map_err(Into::into)
        })
        .collect()
}

// Helper method which returns single DeployResult that is set to be a ipc::WasmError.
pub fn new<E: ExecutionEngineService + Sync + Send + 'static>(
    socket: &str,
    e: E,
) -> grpc::ServerBuilder {
    let socket_path = std::path::Path::new(socket);

    if let Err(e) = std::fs::remove_file(socket_path) {
        if e.kind() != ErrorKind::NotFound {
            panic!("failed to remove old socket file: {:?}", e);
        }
    }

    let mut server = grpc::ServerBuilder::new_plain();
    server.http.set_unix_addr(socket.to_owned()).unwrap();
    server.http.set_cpu_pool_threads(1);
    server.add_service(ipc_grpc::ExecutionEngineServiceServer::new_service_def(e));
    server
}
