use std::marker::{Send, Sync};

use execution_engine::engine::{EngineState, Error as EngineError, ExecutionResult};
use execution_engine::execution::{Error as ExecutionError, Executor, WasmiExecutor};
use ipc::*;
use ipc_grpc::ExecutionEngineService;
use mappings::*;
use std::collections::HashMap;
use storage::gs::{trackingcopy::QueryResult, DbReader};
use storage::history::{self, *};
use storage::transform;
use wasm_prep::{Preprocessor, WasmiPreprocessor};

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
        let key = p.get_base_key().into();
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
                    result.set_success(value.into());
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
        let executor = WasmiExecutor;
        let preprocessor = WasmiPreprocessor;
        let prestate_hash = {
            let mut hash_tmp = [0u8; 32];
            hash_tmp.copy_from_slice(&p.get_parent_state_hash());
            hash_tmp
        };
        let deploys = p.get_deploys();
        let deploys_result: Result<Vec<DeployResult>, RootNotFound> = run_deploys(
            &self,
            &executor,
            &preprocessor,
            prestate_hash,
            deploys.len(),
            deploys,
        );
        let mut exec_response = ipc::ExecResponse::new();
        match deploys_result {
            Ok(deploy_results) => {
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
            let (k, v) = entry.into();
            effects.insert(k, v);
        }
        let result = apply_effect_result_to_ipc(self.apply_effect(prestate_hash, effects));
        grpc::SingleResponse::completed(result)
    }
}

fn run_deploys<A, R: DbReader, H: History<R>, E: Executor<A>, P: Preprocessor<A>>(
    engine_state: &EngineState<R, H>,
    executor: &E,
    preprocessor: &P,
    prestate_hash: [u8; 32],
    deploys_size: usize,
    deploys: &[ipc::Deploy],
) -> Result<Vec<DeployResult>, RootNotFound> {
    let deploy_results: Vec<DeployResult> = Vec::with_capacity(deploys_size);
    deploys
        .iter()
        .try_fold(deploy_results, |mut results, deploy| {
            let module_bytes = &deploy.session_code;
            let address: [u8; 20] = {
                let mut tmp = [0u8; 20];
                tmp.copy_from_slice(&deploy.address);
                tmp
            };
            let timestamp = deploy.timestamp;
            let nonce = deploy.nonce;
            let gas_limit = deploy.gas_limit as u64;
            let result =
                engine_state.run_deploy(
                    module_bytes,
                    address,
                    timestamp,
                    nonce,
                    prestate_hash,
                    gas_limit,
                    executor,
                    preprocessor,
                )?
                .into();
            // We want to treat RootNotFound error differently b/c it should short-circuit
            // the execution of ALL deploys within the block. This is because all of them share
            // the same prestate and all of them would fail.
            // try_for_each will continue only when Ok(_) is returned.
            results.push(result);
            Ok(results)
        })
}

impl From<storage::error::RootNotFound> for ipc::RootNotFound {
    fn from(err: storage::error::RootNotFound) -> ipc::RootNotFound {
        let storage::error::RootNotFound(missing_root_hash) = err;
        let mut root_missing_err = ipc::RootNotFound::new();
        root_missing_err.set_hash(missing_root_hash.to_vec());
        root_missing_err
    }
}

impl Into<DeployResult> for ExecutionResult {
    fn into(self: ExecutionResult) -> DeployResult {
        match self {
            ExecutionResult {
                result: Ok(effects),
                cost,
            } => {
                let mut ipc_ee = execution_effect_to_ipc(effects);
                let deploy_result = {
                    let mut deploy_result_tmp = ipc::DeployResult::new();
                    deploy_result_tmp.set_effects(ipc_ee);
                    deploy_result_tmp.set_cost(cost);
                    deploy_result_tmp
                };
                deploy_result
            }
            ExecutionResult {
                result: Err(err),
                cost,
            } => {
                match err {
                    // TODO(mateusz.gorski): Fix error model for the storage errors.
                    // We don't have separate IPC messages for storage errors
                    // so for the time being they are all reported as "wasm errors".
                    EngineError::StorageError(storage_err) => {
                        use storage::error::Error::*;
                        let mut err = match storage_err {
                            KeyNotFound(key) => {
                                let msg = format!("Key {:?} not found.", key);
                                wasm_error(msg.to_owned())
                            }
                            RkvError(error_msg) => wasm_error(error_msg),
                            TransformTypeMismatch(transform::TypeMismatch { expected, found }) => {
                                let msg = format!(
                                    "Type mismatch. Expected {:?}, found {:?}",
                                    expected, found
                                );
                                wasm_error(msg)
                            }
                            BytesRepr(bytesrepr_err) => {
                                let msg =
                                    format!("Error with byte representation: {:?}", bytesrepr_err);
                                wasm_error(msg)
                            }
                        };
                        err.set_cost(cost);
                        err
                    }
                    EngineError::PreprocessingError(err_msg) => {
                        let mut err = wasm_error(err_msg);
                        err.set_cost(cost);
                        err
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
                            deploy_result
                        }
                        // TODO(mateusz.gorski): Be more specific about execution errors
                        other => {
                            let msg = format!("{:?}", other);
                            let mut err = wasm_error(msg);
                            err.set_cost(cost);
                            err
                        }
                    },
                }
            }
        }
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
        // TODO(mateusz.gorski): We should be more specific about errors here.
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

#[cfg(test)]
mod tests {
    use super::wasm_error;
    use common::key::Key;
    use execution_engine::engine::{Error as EngineError, ExecutionResult};
    use std::collections::HashMap;
    use storage::gs::ExecutionEffect;
    use storage::transform::Transform;

    // Test that wasm_error function actually returns DeployResult with result set to WasmError
    #[test]
    fn wasm_error_result() {
        let error_msg = "WasmError";
        let mut result = wasm_error(error_msg.to_owned());
        assert!(result.has_error());
        let mut ipc_error = result.take_error();
        assert!(ipc_error.has_wasmErr());
        let ipc_wasm_error = ipc_error.take_wasmErr();
        let ipc_error_msg = ipc_wasm_error.get_message();
        assert_eq!(ipc_error_msg, error_msg);
    }

    #[test]
    fn deploy_result_to_ipc_missing_root() {
        let root_hash = [1u8; 32];
        let mut result: super::ipc::RootNotFound = storage::error::RootNotFound(root_hash).into();
        let ipc_missing_hash = result.take_hash();
        assert_eq!(root_hash.to_vec(), ipc_missing_hash);
    }

    #[test]
    fn deploy_result_to_ipc_success() {
        let input_transforms: HashMap<Key, Transform> = {
            let mut tmp_map = HashMap::new();
            tmp_map.insert(Key::Account([1u8; 20]), Transform::AddInt32(10));
            tmp_map
        };
        let execution_effect: ExecutionEffect =
            ExecutionEffect(HashMap::new(), input_transforms.clone());
        let cost: u64 = 123;
        let execution_result: ExecutionResult = ExecutionResult::success(execution_effect, cost);
        let mut ipc_deploy_result: super::ipc::DeployResult = execution_result.into();
        assert_eq!(ipc_deploy_result.get_cost(), cost);

        // Extract transform map from the IPC message and parse it back to the domain
        let ipc_transforms: HashMap<Key, Transform> = {
            let mut ipc_effects = ipc_deploy_result.take_effects();
            let ipc_effects_tnfs = ipc_effects.take_transform_map().into_vec();
            ipc_effects_tnfs
                .iter()
                .map(|e| e.into())
                .collect()
        };
        assert_eq!(&input_transforms, &ipc_transforms);
    }

    fn into_execution_failure<E: Into<EngineError>>(error: E, cost: u64) -> ExecutionResult {
        ExecutionResult::failure(error.into(), cost)
    }

    fn test_cost<E: Into<EngineError>>(expected_cost: u64, err: E) -> u64 {
        let execution_failure = into_execution_failure(err, expected_cost);
        let ipc_deploy_result: super::ipc::DeployResult = execution_failure.into();
        ipc_deploy_result.get_cost()
    }

    #[test]
    fn storage_error_has_cost() {
        use storage::error::Error::*;
        let cost: u64 = 100;
        assert_eq!(test_cost(cost, KeyNotFound(Key::Account([1u8; 20]))), cost);
        assert_eq!(test_cost(cost, RkvError("Error".to_owned())), cost);
        let type_mismatch = storage::transform::TypeMismatch {
            expected: "expected".to_owned(),
            found: "found".to_owned(),
        };
        assert_eq!(test_cost(cost, TransformTypeMismatch(type_mismatch)), cost);
        let bytesrepr_err = common::bytesrepr::Error::EarlyEndOfStream;
        assert_eq!(test_cost(cost, BytesRepr(bytesrepr_err)), cost);
    }

    #[test]
    fn preprocessing_err_has_cost() {
        let cost: u64 = 100;
        // it doesn't matter what error type it is
        let preprocessing_error = wasm_prep::PreprocessingError::NoExportSection;
        assert_eq!(test_cost(cost, preprocessing_error), cost);
    }

    #[test]
    fn exec_err_has_cost() {
        let cost: u64 = 100;
        // GasLimit error is treated differently at the moment so test separately
        assert_eq!(
            test_cost(cost, execution_engine::execution::Error::GasLimit),
            cost
        );
        // for the time being all other execution errors are treated in the same way
        let forged_ref_error =
            execution_engine::execution::Error::ForgedReference(Key::Account([1u8; 20]));
        assert_eq!(test_cost(cost, forged_ref_error), cost);
    }
}
