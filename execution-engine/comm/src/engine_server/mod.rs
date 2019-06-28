use std::convert::TryInto;
use std::fmt::Debug;
use std::io::ErrorKind;
use std::marker::{Send, Sync};
use std::time::Instant;

use common::key::Key;
use common::value::account::{BlockTime, PublicKey};
use common::value::U512;
use execution_engine::engine_state::error::Error as EngineError;
use execution_engine::engine_state::execution_result::ExecutionResult;
use execution_engine::engine_state::genesis::GenesisResult;
use execution_engine::engine_state::EngineState;
use execution_engine::execution::{Executor, WasmiExecutor};
use execution_engine::tracking_copy::QueryResult;
use shared::logging;
use shared::logging::{log_duration, log_info};
use shared::newtypes::{Blake2bHash, CorrelationId};
use storage::global_state::History;
use wasm_prep::wasm_costs::WasmCosts;
use wasm_prep::{Preprocessor, WasmiPreprocessor};

use self::ipc_grpc::ExecutionEngineService;
use self::mappings::*;

pub mod ipc;
pub mod ipc_grpc;
pub mod mappings;
pub mod state;

const EXPECTED_PUBLIC_KEY_LENGTH: usize = 32;

const METRIC_DURATION_COMMIT: &str = "commit_duration";
const METRIC_DURATION_EXEC: &str = "exec_duration";
const METRIC_DURATION_QUERY: &str = "query_duration";
const METRIC_DURATION_VALIDATE: &str = "validate_duration";
const METRIC_DURATION_GENESIS: &str = "genesis_duration";

const TAG_RESPONSE_COMMIT: &str = "commit_response";
const TAG_RESPONSE_EXEC: &str = "exec_response";
const TAG_RESPONSE_QUERY: &str = "query_response";
const TAG_RESPONSE_VALIDATE: &str = "validate_response";
const TAG_RESPONSE_GENESIS: &str = "genesis_response";

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
        let start = Instant::now();
        let correlation_id = CorrelationId::new();
        // TODO: don't unwrap
        let state_hash: Blake2bHash = query_request.get_state_hash().try_into().unwrap();

        let mut tracking_copy = match self.tracking_copy(state_hash) {
            Err(storage_error) => {
                let mut result = ipc::QueryResponse::new();
                let error = format!("Error during checkout out Trie: {:?}", storage_error);
                logging::log_error(&error);
                result.set_failure(error);
                log_duration(
                    correlation_id,
                    METRIC_DURATION_QUERY,
                    "tracking_copy_error",
                    start.elapsed(),
                );
                return grpc::SingleResponse::completed(result);
            }
            Ok(None) => {
                let mut result = ipc::QueryResponse::new();
                let error = format!("Root not found: {:?}", state_hash);
                logging::log_warning(&error);
                result.set_failure(error);
                log_duration(
                    correlation_id,
                    METRIC_DURATION_QUERY,
                    "tracking_copy_root_not_found",
                    start.elapsed(),
                );
                return grpc::SingleResponse::completed(result);
            }
            Ok(Some(tracking_copy)) => tracking_copy,
        };

        let key = match query_request.get_base_key().try_into() {
            Err(ParsingError(err_msg)) => {
                logging::log_error(&err_msg);
                let mut result = ipc::QueryResponse::new();
                result.set_failure(err_msg);
                log_duration(
                    correlation_id,
                    METRIC_DURATION_QUERY,
                    "key_parsing_error",
                    start.elapsed(),
                );
                return grpc::SingleResponse::completed(result);
            }
            Ok(key) => key,
        };

        let path = query_request.get_path();

        let response = match tracking_copy.query(correlation_id, key, path) {
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

        log_duration(
            correlation_id,
            METRIC_DURATION_QUERY,
            TAG_RESPONSE_QUERY,
            start.elapsed(),
        );

        grpc::SingleResponse::completed(response)
    }

    fn exec(
        &self,
        _request_options: ::grpc::RequestOptions,
        exec_request: ipc::ExecRequest,
    ) -> grpc::SingleResponse<ipc::ExecResponse> {
        let start = Instant::now();
        let correlation_id = CorrelationId::new();

        let protocol_version = exec_request.get_protocol_version();

        // TODO: don't unwrap
        let prestate_hash: Blake2bHash = exec_request.get_parent_state_hash().try_into().unwrap();

        let blocktime = BlockTime(exec_request.get_block_time());

        // TODO: don't unwrap
        let wasm_costs = WasmCosts::from_version(protocol_version.value).unwrap();

        let deploys = exec_request.get_deploys();

        let preprocessor: WasmiPreprocessor = WasmiPreprocessor::new(wasm_costs);

        let executor = WasmiExecutor;

        let deploys_result: Result<Vec<ipc::DeployResult>, ipc::RootNotFound> = run_deploys(
            &self,
            &executor,
            &preprocessor,
            prestate_hash,
            blocktime,
            deploys,
            protocol_version,
            correlation_id,
        );

        let exec_response = match deploys_result {
            Ok(deploy_results) => {
                let mut exec_response = ipc::ExecResponse::new();
                let mut exec_result = ipc::ExecResult::new();
                exec_result.set_deploy_results(protobuf::RepeatedField::from_vec(deploy_results));
                exec_response.set_success(exec_result);
                exec_response
            }
            Err(error) => {
                logging::log_error("deploy results error: RootNotFound");
                let mut exec_response = ipc::ExecResponse::new();
                exec_response.set_missing_parent(error);
                exec_response
            }
        };

        log_duration(
            correlation_id,
            METRIC_DURATION_EXEC,
            TAG_RESPONSE_EXEC,
            start.elapsed(),
        );

        grpc::SingleResponse::completed(exec_response)
    }

    fn commit(
        &self,
        _request_options: ::grpc::RequestOptions,
        commit_request: ipc::CommitRequest,
    ) -> grpc::SingleResponse<ipc::CommitResponse> {
        let start = Instant::now();
        let correlation_id = CorrelationId::new();

        // TODO: don't unwrap
        let prestate_hash: Blake2bHash = commit_request.get_prestate_hash().try_into().unwrap();

        let effects_result: Result<CommitTransforms, ParsingError> =
            commit_request.get_effects().try_into();

        let commit_response = match effects_result {
            Err(ParsingError(error_message)) => {
                logging::log_error(&error_message);
                let mut commit_response = ipc::CommitResponse::new();
                let mut err = ipc::PostEffectsError::new();
                err.set_message(error_message);
                commit_response.set_failed_transform(err);
                commit_response
            }
            Ok(effects) => grpc_response_from_commit_result::<H>(
                prestate_hash,
                self.apply_effect(correlation_id, prestate_hash, effects.value()),
            ),
        };

        log_duration(
            correlation_id,
            METRIC_DURATION_COMMIT,
            TAG_RESPONSE_COMMIT,
            start.elapsed(),
        );

        grpc::SingleResponse::completed(commit_response)
    }

    fn validate(
        &self,
        _request_options: ::grpc::RequestOptions,
        validate_request: ipc::ValidateRequest,
    ) -> grpc::SingleResponse<ipc::ValidateResponse> {
        let start = Instant::now();
        let correlation_id = CorrelationId::new();

        let pay_mod = wabt::Module::read_binary(
            validate_request.payment_code,
            &wabt::ReadBinaryOptions::default(),
        )
        .and_then(|x| x.validate());

        log_duration(
            correlation_id,
            METRIC_DURATION_VALIDATE,
            "pay_mod",
            start.elapsed(),
        );

        let ses_mod = wabt::Module::read_binary(
            validate_request.session_code,
            &wabt::ReadBinaryOptions::default(),
        )
        .and_then(|x| x.validate());

        log_duration(
            correlation_id,
            METRIC_DURATION_VALIDATE,
            "ses_mod",
            start.elapsed(),
        );

        let validate_result = match pay_mod.and(ses_mod) {
            Ok(_) => {
                let mut validate_result = ipc::ValidateResponse::new();
                validate_result.set_success(ipc::ValidateResponse_ValidateSuccess::new());
                validate_result
            }
            Err(cause) => {
                let cause_msg = cause.to_string();
                logging::log_error(&cause_msg);

                let mut validate_result = ipc::ValidateResponse::new();
                validate_result.set_failure(cause_msg);
                validate_result
            }
        };

        log_duration(
            correlation_id,
            METRIC_DURATION_VALIDATE,
            TAG_RESPONSE_VALIDATE,
            start.elapsed(),
        );

        grpc::SingleResponse::completed(validate_result)
    }

    #[allow(dead_code)]
    fn run_genesis(
        &self,
        _request_options: ::grpc::RequestOptions,
        genesis_request: ipc::GenesisRequest,
    ) -> ::grpc::SingleResponse<ipc::GenesisResponse> {
        let start = Instant::now();
        let correlation_id = CorrelationId::new();

        let genesis_account_addr = {
            let address = genesis_request.get_address();
            if address.len() != 32 {
                let err_msg =
                    "genesis account public key has to be exactly 32 bytes long.".to_string();
                logging::log_error(&err_msg);

                let mut genesis_response = ipc::GenesisResponse::new();
                let mut genesis_deploy_error = ipc::GenesisDeployError::new();
                genesis_deploy_error.set_message(err_msg);
                genesis_response.set_failed_deploy(genesis_deploy_error);

                log_duration(
                    correlation_id,
                    METRIC_DURATION_GENESIS,
                    TAG_RESPONSE_GENESIS,
                    start.elapsed(),
                );

                return grpc::SingleResponse::completed(genesis_response);
            }

            let mut ret = [0u8; 32];
            ret.clone_from_slice(address);
            ret
        };

        let initial_tokens: U512 = match genesis_request.get_initial_tokens().try_into() {
            Ok(initial_tokens) => initial_tokens,
            Err(err) => {
                let err_msg = format!("{:?}", err);
                logging::log_error(&err_msg);

                let mut genesis_response = ipc::GenesisResponse::new();
                let mut genesis_deploy_error = ipc::GenesisDeployError::new();
                genesis_deploy_error.set_message(err_msg);
                genesis_response.set_failed_deploy(genesis_deploy_error);

                log_duration(
                    correlation_id,
                    METRIC_DURATION_GENESIS,
                    TAG_RESPONSE_GENESIS,
                    start.elapsed(),
                );

                return grpc::SingleResponse::completed(genesis_response);
            }
        };

        let mint_code_bytes = genesis_request.get_mint_code().get_code();

        let proof_of_stake_code_bytes = genesis_request.get_proof_of_stake_code().get_code();

        let genesis_validators_result = genesis_request
            .get_genesis_validators()
            .iter()
            .map(|bond| {
                let address = bond.get_validator_public_key();
                if address.len() != 32 {
                    let err_msg =
                        "Validator public key has to be exactly 32 bytes long".to_string();
                    logging::log_error(&err_msg);

                    let mut genesis_deploy_error = ipc::GenesisDeployError::new();
                    genesis_deploy_error.set_message(err_msg);

                    Err(genesis_deploy_error)
                } else {
                    let mut buff = [0u8; 32];
                    buff.copy_from_slice(&address);
                    let pk = PublicKey::new(buff);
                    let bond: U512 = bond.get_stake().try_into().unwrap();
                    Ok((pk, bond))
                }
            })
            .collect();

        let genesis_validators = match genesis_validators_result {
            Ok(validators) => validators,
            Err(genesis_error) => {
                let mut genesis_response = ipc::GenesisResponse::new();
                genesis_response.set_failed_deploy(genesis_error);

                log_duration(
                    correlation_id,
                    METRIC_DURATION_GENESIS,
                    TAG_RESPONSE_GENESIS,
                    start.elapsed(),
                );

                return grpc::SingleResponse::completed(genesis_response);
            }
        };

        let protocol_version = genesis_request.get_protocol_version().value;

        let genesis_response = match self.commit_genesis(
            correlation_id,
            genesis_account_addr,
            initial_tokens,
            mint_code_bytes,
            proof_of_stake_code_bytes,
            genesis_validators,
            protocol_version,
        ) {
            Ok(GenesisResult::Success {
                post_state_hash,
                effect,
            }) => {
                let success_message = format!("run_genesis successful: {}", post_state_hash);
                log_info(&success_message);

                let mut genesis_response = ipc::GenesisResponse::new();
                let mut genesis_result = ipc::GenesisResult::new();
                genesis_result.set_poststate_hash(post_state_hash.to_vec());
                genesis_result.set_effect(effect.into());
                genesis_response.set_success(genesis_result);
                genesis_response
            }
            Ok(genesis_result) => {
                let err_msg = genesis_result.to_string();
                logging::log_error(&err_msg);

                let mut genesis_response = ipc::GenesisResponse::new();
                let mut genesis_deploy_error = ipc::GenesisDeployError::new();
                genesis_deploy_error.set_message(err_msg);
                genesis_response.set_failed_deploy(genesis_deploy_error);
                genesis_response
            }
            Err(err) => {
                let err_msg = err.to_string();
                logging::log_error(&err_msg);

                let mut genesis_response = ipc::GenesisResponse::new();
                let mut genesis_deploy_error = ipc::GenesisDeployError::new();
                genesis_deploy_error.set_message(err_msg);
                genesis_response.set_failed_deploy(genesis_deploy_error);
                genesis_response
            }
        };

        log_duration(
            correlation_id,
            METRIC_DURATION_GENESIS,
            TAG_RESPONSE_GENESIS,
            start.elapsed(),
        );

        grpc::SingleResponse::completed(genesis_response)
    }
}

#[allow(clippy::too_many_arguments)]
fn run_deploys<A, H, E, P>(
    engine_state: &EngineState<H>,
    executor: &E,
    preprocessor: &P,
    prestate_hash: Blake2bHash,
    blocktime: BlockTime,
    deploys: &[ipc::Deploy],
    protocol_version: &state::ProtocolVersion,
    correlation_id: CorrelationId,
) -> Result<Vec<ipc::DeployResult>, ipc::RootNotFound>
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
            let address = {
                let address_len = deploy.address.len();
                if address_len != EXPECTED_PUBLIC_KEY_LENGTH {
                    let err = EngineError::InvalidPublicKeyLength {
                        expected: EXPECTED_PUBLIC_KEY_LENGTH,
                        actual: address_len,
                    };
                    let failure = ExecutionResult::precondition_failure(err);
                    return Ok(failure.into());
                }
                let mut dest = [0; EXPECTED_PUBLIC_KEY_LENGTH];
                dest.copy_from_slice(&deploy.address);
                Key::Account(dest)
            };

            let nonce = deploy.nonce;
            let gas_limit = deploy.gas_limit as u64;
            let protocol_version = protocol_version.value;
            engine_state
                .run_deploy(
                    module_bytes,
                    args,
                    address,
                    blocktime,
                    nonce,
                    prestate_hash,
                    gas_limit,
                    protocol_version,
                    correlation_id,
                    executor,
                    preprocessor,
                )
                .map(Into::into)
                .map_err(Into::into)
        })
        .collect()
}

// Helper method which returns single DeployResult that is set to be a WasmError.
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
