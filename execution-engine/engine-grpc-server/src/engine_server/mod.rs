pub mod ipc;
pub mod ipc_grpc;
pub mod mappings;
pub mod state;
pub mod transforms;

use std::{
    collections::BTreeMap,
    convert::{TryFrom, TryInto},
    fmt::Debug,
    io::ErrorKind,
    marker::{Send, Sync},
    time::Instant,
};

use grpc::SingleResponse;

use contract_ffi::value::{account::BlockTime, ProtocolVersion};
use engine_core::{
    engine_state::{
        deploy_item::DeployItem,
        execution_result::ExecutionResult,
        genesis::{GenesisConfig, GenesisResult},
        upgrade::{UpgradeConfig, UpgradeResult},
        EngineState, Error as EngineError,
    },
    execution::Executor,
    tracking_copy::QueryResult,
};
use engine_shared::{
    logging::{self, log_duration, log_info, log_level::LogLevel},
    newtypes::{Blake2bHash, CorrelationId, BLAKE2B_DIGEST_LENGTH},
};
use engine_storage::global_state::{CommitResult, StateProvider};
use engine_wasm_prep::Preprocessor;

use self::{
    ipc_grpc::ExecutionEngineService,
    mappings::{CommitTransforms, MappingError, ParsingError},
};

const METRIC_DURATION_COMMIT: &str = "commit_duration";
const METRIC_DURATION_EXEC: &str = "exec_duration";
const METRIC_DURATION_QUERY: &str = "query_duration";
const METRIC_DURATION_GENESIS: &str = "genesis_duration";
const METRIC_DURATION_UPGRADE: &str = "upgrade_duration";

const TAG_RESPONSE_COMMIT: &str = "commit_response";
const TAG_RESPONSE_EXEC: &str = "exec_response";
const TAG_RESPONSE_QUERY: &str = "query_response";
const TAG_RESPONSE_GENESIS: &str = "genesis_response";
const TAG_RESPONSE_UPGRADE: &str = "upgrade_response";

const DEFAULT_PROTOCOL_VERSION: ProtocolVersion = ProtocolVersion::V1_0_0;

// Idea is that Engine will represent the core of the execution engine project.
// It will act as an entry point for execution of Wasm binaries.
// Proto definitions should be translated into domain objects when Engine's API
// is invoked. This way core won't depend on casperlabs-engine-grpc-server
// (outer layer) leading to cleaner design.
impl<S> ipc_grpc::ExecutionEngineService for EngineState<S>
where
    S: StateProvider,
    EngineError: From<S::Error>,
    S::Error: Into<engine_core::execution::Error> + Debug,
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

    fn execute(
        &self,
        _request_options: ::grpc::RequestOptions,
        exec_request: ipc::ExecuteRequest,
    ) -> grpc::SingleResponse<ipc::ExecuteResponse> {
        let start = Instant::now();
        let correlation_id = CorrelationId::new();

        let parent_state_hash = {
            let parent_state_hash = exec_request.get_parent_state_hash();
            match Blake2bHash::try_from(parent_state_hash) {
                Ok(hash) => hash,
                Err(_) => {
                    // TODO: do not panic
                    let length = parent_state_hash.len();
                    panic!(
                        "Invalid hash. Expected length: {:?}, actual length: {:?}",
                        BLAKE2B_DIGEST_LENGTH, length
                    )
                }
            }
        };
        let block_time = BlockTime::new(exec_request.get_block_time());
        let protocol_version = exec_request.get_protocol_version().into();
        // TODO: do not unwrap
        let wasm_costs = self.wasm_costs(protocol_version).unwrap().unwrap();
        let executor = Executor;
        let preprocessor = Preprocessor::new(wasm_costs);

        let mut exec_response = ipc::ExecuteResponse::new();
        let mut results: Vec<ExecutionResult> = Vec::new();

        for result in exec_request
            .get_deploys()
            .iter()
            .map::<Result<DeployItem, MappingError>, _>(TryInto::try_into)
        {
            match result {
                Ok(deploy_item) => {
                    let result = self.deploy(
                        correlation_id,
                        &executor,
                        &preprocessor,
                        protocol_version,
                        parent_state_hash,
                        block_time,
                        deploy_item,
                    );
                    match result {
                        Ok(result) => results.push(result),
                        Err(error) => {
                            logging::log_error("deploy results error: RootNotFound");
                            exec_response.set_missing_parent(error.into());
                            log_duration(
                                correlation_id,
                                METRIC_DURATION_EXEC,
                                TAG_RESPONSE_EXEC,
                                start.elapsed(),
                            );
                            return grpc::SingleResponse::completed(exec_response);
                        }
                    };
                }
                Err(mapping_error) => {
                    results.push(ExecutionResult::precondition_failure(mapping_error.into()))
                }
            }
        }

        let results: Vec<ipc::DeployResult> = results.into_iter().map(Into::into).collect();
        let mut exec_result = ipc::ExecResult::new();
        exec_result.set_deploy_results(protobuf::RepeatedField::from_vec(results));
        exec_response.set_success(exec_result);
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

        // TODO
        let protocol_version = {
            let protocol_version = commit_request.get_protocol_version().into();
            if protocol_version < DEFAULT_PROTOCOL_VERSION {
                DEFAULT_PROTOCOL_VERSION
            } else {
                protocol_version
            }
        };

        // Acquire pre-state hash
        let pre_state_hash: Blake2bHash = match commit_request.get_prestate_hash().try_into() {
            Err(_) => {
                let error_message = "Could not parse pre-state hash".to_string();
                logging::log_error(&error_message);

                let err = {
                    let mut tmp = ipc::PostEffectsError::new();
                    tmp.set_message(error_message);
                    tmp
                };
                let mut commit_response = ipc::CommitResponse::new();
                commit_response.set_failed_transform(err);
                return SingleResponse::completed(commit_response);
            }
            Ok(hash) => hash,
        };

        // Acquire commit transforms
        let transforms: CommitTransforms = match commit_request.get_effects().try_into() {
            Err(ParsingError(error_message)) => {
                logging::log_error(&error_message);

                let err = {
                    let mut tmp = ipc::PostEffectsError::new();
                    tmp.set_message(error_message);
                    tmp
                };
                let mut commit_response = ipc::CommitResponse::new();
                commit_response.set_failed_transform(err);
                return SingleResponse::completed(commit_response);
            }
            Ok(transforms) => transforms,
        };

        // "Apply" effects to global state
        let commit_response = {
            let mut ret = ipc::CommitResponse::new();

            match self.apply_effect(
                correlation_id,
                protocol_version,
                pre_state_hash,
                transforms.value(),
            ) {
                Ok(CommitResult::Success {
                    state_root,
                    bonded_validators,
                }) => {
                    let properties = {
                        let mut tmp = BTreeMap::new();
                        tmp.insert("post-state-hash".to_string(), format!("{:?}", state_root));
                        tmp.insert("success".to_string(), true.to_string());
                        tmp
                    };
                    logging::log_details(
                        LogLevel::Info,
                        "effects applied; new state hash is: {post-state-hash}".to_owned(),
                        properties,
                    );

                    let bonds = bonded_validators.into_iter().map(Into::into).collect();
                    let commit_result = {
                        let mut tmp = ipc::CommitResult::new();
                        tmp.set_poststate_hash(state_root.to_vec());
                        tmp.set_bonded_validators(bonds);
                        tmp
                    };
                    ret.set_success(commit_result);
                }
                Ok(CommitResult::RootNotFound) => {
                    logging::log_warning("RootNotFound");

                    let root_not_found = {
                        let mut tmp = ipc::RootNotFound::new();
                        tmp.set_hash(pre_state_hash.to_vec());
                        tmp
                    };
                    ret.set_missing_prestate(root_not_found);
                }
                Ok(CommitResult::KeyNotFound(key)) => {
                    logging::log_warning("KeyNotFound");

                    ret.set_key_not_found(key.into());
                }
                Ok(CommitResult::TypeMismatch(type_mismatch)) => {
                    logging::log_warning("TypeMismatch");

                    ret.set_type_mismatch(type_mismatch.into());
                }
                Err(error) => {
                    let log_message = format!("State error {:?} when applying transforms", error);
                    logging::log_error(&log_message);

                    let err = {
                        let mut tmp = ipc::PostEffectsError::new();
                        tmp.set_message(format!("{:?}", error));
                        tmp
                    };
                    ret.set_failed_transform(err);
                }
            }

            ret
        };

        log_duration(
            correlation_id,
            METRIC_DURATION_COMMIT,
            TAG_RESPONSE_COMMIT,
            start.elapsed(),
        );

        grpc::SingleResponse::completed(commit_response)
    }

    fn run_genesis(
        &self,
        _request_options: ::grpc::RequestOptions,
        genesis_config: ipc::ChainSpec_GenesisConfig,
    ) -> ::grpc::SingleResponse<ipc::GenesisResponse> {
        let start = Instant::now();
        let correlation_id = CorrelationId::new();

        let genesis_config: GenesisConfig = match genesis_config.try_into() {
            Ok(genesis_config) => genesis_config,
            Err(error) => {
                let err_msg = error.to_string();
                logging::log_error(&err_msg);

                let mut genesis_response = ipc::GenesisResponse::new();
                let mut genesis_deploy_error = ipc::GenesisDeployError::new();
                genesis_deploy_error.set_message(err_msg);
                genesis_response.set_failed_deploy(genesis_deploy_error);
                return grpc::SingleResponse::completed(genesis_response);
            }
        };

        let genesis_response = match self.commit_genesis(correlation_id, genesis_config) {
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

    fn upgrade(
        &self,
        _request_options: ::grpc::RequestOptions,
        upgrade_request: ipc::UpgradeRequest,
    ) -> ::grpc::SingleResponse<ipc::UpgradeResponse> {
        let start = Instant::now();
        let correlation_id = CorrelationId::new();

        let upgrade_config: UpgradeConfig = match upgrade_request.try_into() {
            Ok(upgrade_config) => upgrade_config,
            Err(error) => {
                let err_msg = error.to_string();
                logging::log_error(&err_msg);

                let mut upgrade_deploy_error = ipc::UpgradeDeployError::new();
                upgrade_deploy_error.set_message(err_msg);
                let mut upgrade_response = ipc::UpgradeResponse::new();
                upgrade_response.set_failed_deploy(upgrade_deploy_error);

                log_duration(
                    correlation_id,
                    METRIC_DURATION_UPGRADE,
                    TAG_RESPONSE_UPGRADE,
                    start.elapsed(),
                );

                return grpc::SingleResponse::completed(upgrade_response);
            }
        };

        let upgrade_response = match self.commit_upgrade(correlation_id, upgrade_config) {
            Ok(UpgradeResult::Success {
                post_state_hash,
                effect,
            }) => {
                let success_message = format!("upgrade successful: {}", post_state_hash);
                log_info(&success_message);

                let mut upgrade_result = ipc::UpgradeResult::new();
                upgrade_result.set_post_state_hash(post_state_hash.to_vec());
                upgrade_result.set_effect(effect.into());

                let mut ret = ipc::UpgradeResponse::new();
                ret.set_success(upgrade_result);
                ret
            }
            Ok(upgrade_result) => {
                let err_msg = upgrade_result.to_string();
                logging::log_error(&err_msg);

                let mut upgrade_deploy_error = ipc::UpgradeDeployError::new();
                upgrade_deploy_error.set_message(err_msg);

                let mut ret = ipc::UpgradeResponse::new();
                ret.set_failed_deploy(upgrade_deploy_error);
                ret
            }
            Err(err) => {
                let err_msg = err.to_string();
                logging::log_error(&err_msg);

                let mut upgrade_deploy_error = ipc::UpgradeDeployError::new();
                upgrade_deploy_error.set_message(err_msg);

                let mut ret = ipc::UpgradeResponse::new();
                ret.set_failed_deploy(upgrade_deploy_error);
                ret
            }
        };

        log_duration(
            correlation_id,
            METRIC_DURATION_UPGRADE,
            TAG_RESPONSE_UPGRADE,
            start.elapsed(),
        );

        grpc::SingleResponse::completed(upgrade_response)
    }
}

// Helper method which returns single DeployResult that is set to be a
// WasmError.
pub fn new<E: ExecutionEngineService + Sync + Send + 'static>(
    socket: &str,
    thread_count: usize,
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
    server.http.set_cpu_pool_threads(thread_count);
    server.add_service(ipc_grpc::ExecutionEngineServiceServer::new_service_def(e));
    server
}
