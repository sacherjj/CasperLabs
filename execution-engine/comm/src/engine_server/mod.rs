use std::marker::{Send, Sync};

use execution_engine::engine::{EngineState, Error as EngineError, ExecutionResult};
use execution_engine::execution::{Error as ExecutionError, WasmiExecutor};
use ipc::*;
use ipc_grpc::ExecutionEngineService;
use mappings::*;
use std::collections::HashMap;
use storage::gs::{trackingcopy::QueryResult, DbReader};
use storage::history::{self, *};
use storage::transform;
use wasm_prep::WasmiPreprocessor;

pub mod ipc;
pub mod ipc_grpc;
pub mod mappings;

// Idea is that Engine will represent the core of the execution engine project.
// It will act as an entry point for execution of Wasm binaries.
// Proto definitions should be translated into domain objects when Engine's API is invoked.
// This way core won't depend on comm (outer layer) leading to cleaner design.
impl<R: DbReader, H: History<R>> ipc_grpc::ExecutionEngineService for EngineState<R, H> {
    fn query(
        &self,
        _o: ::grpc::RequestOptions,
        p: ipc::QueryRequest,
    ) -> grpc::SingleResponse<ipc::QueryResponse> {
        let mut state_hash = [0u8; 32];
        state_hash.copy_from_slice(&p.get_state_hash());
        let key = ipc_to_key(p.get_base_key());
        let path = p.get_path();

        if let Ok(mut tc) = self.tracking_copy(state_hash) {
            let response = match tc.query(key, path) {
                Err(err) => {
                    let mut result = ipc::QueryResponse::new();
                    let error = format!("{:?}", err);
                    result.set_failure(error);
                    result
                }

                Ok(QueryResult::ValueNotFound(full_path)) => {
                    let mut result = ipc::QueryResponse::new();
                    let error = format!("Value not found: {:?}", full_path);
                    result.set_failure(error);
                    result
                }

                Ok(QueryResult::Success(value)) => {
                    let mut result = ipc::QueryResponse::new();
                    result.set_success(value_to_ipc(&value));
                    result
                }
            };

            grpc::SingleResponse::completed(response)
        } else {
            let mut result = ipc::QueryResponse::new();
            let error = format!("Root not found: {:?}", state_hash);
            result.set_failure(error);
            grpc::SingleResponse::completed(result)
        }
    }

    fn exec(
        &self,
        _o: ::grpc::RequestOptions,
        p: ipc::ExecRequest,
    ) -> grpc::SingleResponse<ipc::ExecResponse> {
        let executor = WasmiExecutor {};
        let preprocessor = WasmiPreprocessor {};
        let prestate_hash = {
            let mut hash_tmp = [0u8; 32];
            hash_tmp.copy_from_slice(&p.get_parent_state_hash());
            hash_tmp
        };
        let deploys = p.get_deploys();
        let mut deploy_results: Vec<DeployResult> = Vec::with_capacity(deploys.len());
        let fold_result: Result<(), RootNotFound> = deploys.iter().try_for_each(|deploy| {
            let module_bytes = &deploy.session_code;
            let address: [u8; 20] = {
                let mut tmp = [0u8; 20];
                tmp.copy_from_slice(&deploy.address);
                tmp
            };
            let timestamp = deploy.timestamp;
            let nonce = deploy.nonce;
            let gas_limit = deploy.gas_limit as u64;
            let deploy_result: Result<DeployResult, RootNotFound> = match self.run_deploy(
                module_bytes,
                address,
                timestamp,
                nonce,
                prestate_hash,
                gas_limit,
                &executor,
                &preprocessor,
            ) {
                // We want to treat RootNotFound error differently b/c it should short-circuit
                // the execution of ALL deploys within the block. This is because all of them share the same prestate
                // and all of them would fail.
                Err(storage::error::RootNotFound(missing_root_hash)) => {
                    let mut root_missing_err = ipc::RootNotFound::new();
                    root_missing_err.set_hash(missing_root_hash.to_vec());
                    Err(root_missing_err)
                }
                Ok(ExecutionResult::Success(effects, cost)) => {
                    let mut ipc_ee = execution_effect_to_ipc(effects);
                    let deploy_result = {
                        let mut deploy_result_tmp = ipc::DeployResult::new();
                        deploy_result_tmp.set_effects(ipc_ee);
                        deploy_result_tmp.set_cost(cost);
                        deploy_result_tmp
                    };
                    Ok(deploy_result)
                }
                Ok(ExecutionResult::Failure(err, cost)) => {
                    //TODO(mateusz.gorski) Tests!
                    match err {
                        EngineError::StorageError(storage_err) => {
                            use storage::error::Error::*;
                            let mut err = match storage_err {
                                KeyNotFound(key) => {
                                    let msg = format!("Key {:?} not found.", key);
                                    wasm_error(msg.to_owned())
                                }
                                RkvError(error_msg) => wasm_error(error_msg),
                                TransformTypeMismatch(transform::TypeMismatch {
                                    expected,
                                    found,
                                }) => {
                                    let msg = format!(
                                        "Type mismatch. Expected {:?}, found {:?}",
                                        expected, found
                                    );
                                    wasm_error(msg)
                                }
                                BytesRepr(bytesrepr_err) => {
                                    let msg = format!(
                                        "Error with byte representation: {:?}",
                                        bytesrepr_err
                                    );
                                    wasm_error(msg)
                                }
                            };
                            err.set_cost(cost);
                            Ok(err)
                        }
                        EngineError::PreprocessingError(err_msg) => {
                            let mut err = wasm_error(err_msg);
                            err.set_cost(cost);
                            Ok(err)
                        }
                        EngineError::ExecError(exec_error) => match exec_error {
                            ExecutionError::GasLimit => {
                                let mut deploy_result = {
                                    let mut deploy_result_tmp = ipc::DeployResult::new();
                                    let mut deploy_error = ipc::DeployError::new();
                                    deploy_error.set_gasErr(ipc::OutOfGasError::new());
                                    deploy_result_tmp.set_error(deploy_error);
                                    deploy_result_tmp.set_cost(cost);
                                    deploy_result_tmp
                                };
                                Ok(deploy_result)
                            }
                            //TODO(mateusz.gorski): Be more specific about execution errors
                            other => {
                                let msg = format!("{:?}", other);
                                let mut err = wasm_error(msg);
                                err.set_cost(cost);
                                Ok(err)
                            }
                        },
                    }
                }
            };
            match deploy_result {
                Ok(result) => {
                    deploy_results.push(result);
                    Ok(())
                }
                Err(root_missing_err) => Err(root_missing_err),
            }
        });
        let mut exec_response = ipc::ExecResponse::new();
        match fold_result {
            Ok(_) => {
                let mut exec_result = ipc::ExecResult::new();
                exec_result.set_deploy_results(protobuf::RepeatedField::from_vec(deploy_results));
                exec_response.set_success(exec_result);
                grpc::SingleResponse::completed(exec_response)
            }
            Err(error) => {
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
        let mut prestate_hash = [0u8; 32];
        prestate_hash.copy_from_slice(&p.get_prestate_hash());
        let mut effects = HashMap::new();
        for entry in p.get_effects().iter() {
            let (k, v) = transform_entry_to_key_transform(entry);
            effects.insert(k, v);
        }
        let result = apply_effect_result_to_ipc(self.apply_effect(prestate_hash, effects));
        grpc::SingleResponse::completed(result)
    }
}

fn apply_effect_result_to_ipc(
    input: Result<storage::history::CommitResult, storage::error::RootNotFound>,
) -> ipc::CommitResponse {
    match input {
        Err(storage::error::RootNotFound(missing_root_hash)) => {
            let mut err = ipc::RootNotFound::new();
            let mut tmp_res = ipc::CommitResponse::new();
            err.set_hash(missing_root_hash.to_vec());
            tmp_res.set_missing_prestate(err);
            tmp_res
        }
        Ok(history::CommitResult::Success(post_state_hash)) => {
            println!("Effects applied. New state hash is: {:?}", post_state_hash);
            let mut commit_result = ipc::CommitResult::new();
            let mut tmp_res = ipc::CommitResponse::new();
            commit_result.set_poststate_hash(post_state_hash.to_vec());
            tmp_res.set_success(commit_result);
            tmp_res
        }
        //TODO(mateusz.gorski): We should be more specific about errors here.
        Ok(history::CommitResult::Failure(storage_error)) => {
            println!("Error {:?} when applying effects", storage_error);
            let mut err = ipc::PostEffectsError::new();
            let mut tmp_res = ipc::CommitResponse::new();
            err.set_message(format!("{:?}", storage_error));
            tmp_res.set_failed_transform(err);
            tmp_res
        }
    }
}

// Helper method which returns single DeployResult that is set to be a WasmError.
fn wasm_error(msg: String) -> DeployResult {
    let mut deploy_result = ipc::DeployResult::new();
    let mut deploy_error = ipc::DeployError::new();
    let mut err = ipc::WasmError::new();
    err.set_message(msg.to_owned());
    deploy_error.set_wasmErr(err);
    deploy_result.set_error(deploy_error);
    deploy_result
}

pub fn new<E: ExecutionEngineService + Sync + Send + 'static>(
    socket: &str,
    e: E,
) -> grpc::ServerBuilder {
    let socket_path = std::path::Path::new(socket);
    if socket_path.exists() {
        std::fs::remove_file(socket_path).expect("Remove old socket file.");
    }

    let mut server = grpc::ServerBuilder::new_plain();
    server.http.set_unix_addr(socket.to_owned()).unwrap();
    server.http.set_cpu_pool_threads(1);
    server.add_service(ipc_grpc::ExecutionEngineServiceServer::new_service_def(e));
    server
}
