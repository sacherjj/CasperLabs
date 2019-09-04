use std::collections::{BTreeSet, HashMap};
use std::convert::TryFrom;
use std::convert::TryInto;
use std::fmt::Debug;
use std::io::ErrorKind;
use std::marker::{Send, Sync};
use std::time::Instant;

use crate::engine_server::ipc::CommitResponse;
use contract_ffi::key::Key;
use contract_ffi::value::account::{BlockTime, PublicKey};
use contract_ffi::value::U512;
use engine_core::engine_state::error::Error as EngineError;
use engine_core::engine_state::execution_result::ExecutionResult;
use engine_core::engine_state::genesis::GenesisURefsSource;
use engine_core::engine_state::{
    genesis::GenesisResult, get_bonded_validators, EngineState, GetBondedValidatorsError,
};
use engine_core::execution::{Executor, WasmiExecutor};
use engine_core::tracking_copy::QueryResult;
use engine_shared::logging;
use engine_shared::logging::{log_duration, log_info};
use engine_shared::newtypes::{Blake2bHash, CorrelationId};
use engine_storage::global_state::{CommitResult, History};
use engine_wasm_prep::wasm_costs::WasmCosts;
use engine_wasm_prep::{Preprocessor, WasmiPreprocessor};

use self::ipc_grpc::ExecutionEngineService;
use self::mappings::*;

pub mod ipc;
pub mod ipc_grpc;
pub mod mappings;
pub mod state;
pub mod transforms;

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
// Proto definitions should be translated into domain objects when Engine's API
// is invoked. This way core won't depend on casperlabs-engine-grpc-server
// (outer layer) leading to cleaner design.
impl<H> ipc_grpc::ExecutionEngineService for EngineState<H>
where
    H: History,
    EngineError: From<H::Error>,
    H::Error: Into<engine_core::execution::Error> + Debug,
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

    fn execute(
        &self,
        _request_options: ::grpc::RequestOptions,
        exec_request: ipc::ExecuteRequest,
    ) -> grpc::SingleResponse<ipc::ExecuteResponse> {
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

        let deploys_result: Result<Vec<ipc::DeployResult>, ipc::RootNotFound> = execute_deploys(
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
                let mut exec_response = ipc::ExecuteResponse::new();
                let mut exec_result = ipc::ExecResult::new();
                exec_result.set_deploy_results(protobuf::RepeatedField::from_vec(deploy_results));
                exec_response.set_success(exec_result);
                exec_response
            }
            Err(error) => {
                logging::log_error("deploy results error: RootNotFound");
                let mut exec_response = ipc::ExecuteResponse::new();
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

            Ok(effects) => {
                let commit_result =
                    self.apply_effect(correlation_id, prestate_hash, effects.value());
                if let Ok(engine_storage::global_state::CommitResult::Success(poststate_hash)) =
                    commit_result
                {
                    let pos_key = Key::URef(GenesisURefsSource::default().get_pos_address());
                    let bonded_validators_res = get_bonded_validators(
                        self.state(),
                        poststate_hash,
                        &pos_key,
                        correlation_id,
                    );
                    bonded_validators_and_commit_result(
                        prestate_hash,
                        poststate_hash,
                        commit_result,
                        bonded_validators_res,
                    )
                } else {
                    // Commit unsuccessful.
                    grpc_response_from_commit_result::<H>(prestate_hash, commit_result)
                }
            }
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

        let initial_motes: U512 = match genesis_request.get_initial_motes().try_into() {
            Ok(initial_motes) => initial_motes,
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
                to_domain_validators(bond).map_err(|err_msg| {
                    logging::log_error(&err_msg);
                    let mut genesis_deploy_error = ipc::GenesisDeployError::new();
                    genesis_deploy_error.set_message(err_msg);
                    genesis_deploy_error
                })
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
            initial_motes,
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

    fn run_genesis_with_chainspec(
        &self,
        _request_options: ::grpc::RequestOptions,
        _genesis_config: ipc::ChainSpec_GenesisConfig,
    ) -> ::grpc::SingleResponse<ipc::GenesisResponse> {
        let mut genesis_response = ipc::GenesisResponse::new();
        let mut genesis_deploy_error = ipc::GenesisDeployError::new();
        let err_msg = String::from("Unimplemented!");

        genesis_deploy_error.set_message(err_msg);
        genesis_response.set_failed_deploy(genesis_deploy_error);

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
    H::Error: Into<engine_core::execution::Error>,
{
    // We want to treat RootNotFound error differently b/c it should short-circuit
    // the execution of ALL deploys within the block. This is because all of them
    // share the same prestate and all of them would fail.
    // Iterator (Result<_, _> + collect()) will short circuit the execution
    // when run_deploy returns Err.
    deploys
        .iter()
        .map(|deploy| {
            let session = deploy.get_session();
            let session_module_bytes = &session.code;
            let session_args = &session.args;

            let payment = deploy.get_payment();
            let payment_module_bytes = &payment.code;
            let payment_args = &payment.args;

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

            // Parse all authorization keys from IPC into a vector
            let authorized_keys: BTreeSet<PublicKey> = {
                let maybe_keys: Result<BTreeSet<_>, EngineError> = deploy
                    .authorization_keys
                    .iter()
                    .map(|key_bytes| {
                        // Try to convert an element of bytes into a possibly
                        // valid PublicKey with error handling
                        PublicKey::try_from(key_bytes.as_slice()).map_err(|_| {
                            EngineError::InvalidPublicKeyLength {
                                expected: EXPECTED_PUBLIC_KEY_LENGTH,
                                actual: key_bytes.len(),
                            }
                        })
                    })
                    .collect();

                match maybe_keys {
                    Ok(keys) => keys,
                    Err(error) => return Ok(ExecutionResult::precondition_failure(error).into()),
                }
            };

            let nonce = deploy.nonce;
            let protocol_version = protocol_version.value;
            engine_state
                .run_deploy(
                    session_module_bytes,
                    session_args,
                    payment_module_bytes,
                    payment_args,
                    address,
                    authorized_keys,
                    blocktime,
                    nonce,
                    prestate_hash,
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

#[allow(clippy::too_many_arguments)]
fn execute_deploys<A, H, E, P>(
    engine_state: &EngineState<H>,
    executor: &E,
    preprocessor: &P,
    prestate_hash: Blake2bHash,
    blocktime: BlockTime,
    deploys: &[ipc::DeployItem],
    protocol_version: &state::ProtocolVersion,
    correlation_id: CorrelationId,
) -> Result<Vec<ipc::DeployResult>, ipc::RootNotFound>
where
    H: History,
    E: Executor<A>,
    P: Preprocessor<A>,
    EngineError: From<H::Error>,
    H::Error: Into<engine_core::execution::Error>,
{
    // We want to treat RootNotFound error differently b/c it should short-circuit
    // the execution of ALL deploys within the block. This is because all of them
    // share the same prestate and all of them would fail.
    // Iterator (Result<_, _> + collect()) will short circuit the execution
    // when run_deploy returns Err.
    deploys
        .iter()
        .map(|deploy| {
            let session_payload = match deploy.get_session().to_owned().payload {
                Some(payload) => payload.into(),
                None => {
                    return Ok(
                        ExecutionResult::precondition_failure(EngineError::DeployError).into(),
                    )
                }
            };

            let payment_payload = match deploy.get_payment().to_owned().payload {
                Some(payload) => payload.into(),
                None => {
                    return Ok(
                        ExecutionResult::precondition_failure(EngineError::DeployError).into(),
                    )
                }
            };

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

            // Parse all authorization keys from IPC into a vector
            let authorization_keys: BTreeSet<PublicKey> = {
                let maybe_keys: Result<BTreeSet<_>, EngineError> = deploy
                    .authorization_keys
                    .iter()
                    .map(|key_bytes| {
                        // Try to convert an element of bytes into a possibly
                        // valid PublicKey with error handling
                        PublicKey::try_from(key_bytes.as_slice()).map_err(|_| {
                            EngineError::InvalidPublicKeyLength {
                                expected: EXPECTED_PUBLIC_KEY_LENGTH,
                                actual: key_bytes.len(),
                            }
                        })
                    })
                    .collect();

                match maybe_keys {
                    Ok(keys) => keys,
                    Err(error) => return Ok(ExecutionResult::precondition_failure(error).into()),
                }
            };

            let nonce = deploy.nonce;
            let protocol_version = protocol_version.value;
            engine_state
                .run_deploy_item(
                    session_payload,
                    payment_payload,
                    address,
                    authorization_keys,
                    blocktime,
                    nonce,
                    prestate_hash,
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

// TODO: Refactor.
#[allow(clippy::implicit_hasher)]
pub fn bonded_validators_and_commit_result<H>(
    prestate_hash: Blake2bHash,
    poststate_hash: Blake2bHash,
    commit_result: Result<CommitResult, H::Error>,
    bonded_validators: Result<HashMap<PublicKey, U512>, GetBondedValidatorsError<H>>,
) -> CommitResponse
where
    H: History,
    H::Error: Into<EngineError> + std::fmt::Debug,
{
    match bonded_validators {
        Ok(bonded_validators) => {
            let mut grpc_response =
                grpc_response_from_commit_result::<H>(prestate_hash, commit_result);
            let grpc_bonded_validators = bonded_validators
                .iter()
                .map(|(pk, bond)| {
                    let mut ipc_bond = ipc::Bond::new();
                    ipc_bond.set_stake((*bond).into());
                    ipc_bond.set_validator_public_key(pk.value().to_vec());
                    ipc_bond
                })
                .collect::<Vec<ipc::Bond>>()
                .into();
            grpc_response
                .mut_success() // We know it's a success because of the check few lines earlier.
                .set_bonded_validators(grpc_bonded_validators);
            grpc_response
        }
        Err(GetBondedValidatorsError::StorageErrors(error)) => {
            grpc_response_from_commit_result::<H>(poststate_hash, Err(error))
        }
        Err(GetBondedValidatorsError::PostStateHashNotFound(root_hash)) => {
            // I am not sure how to parse this error. It would mean that most probably
            // we have screwed up something in the trie store because `root_hash` was
            // calculated by us just a moment ago. It [root_hash] is a `poststate_hash` we
            // return to the node. There is no proper error variant in the
            // `engine_storage::error::Error` for it though.
            let error_message = format!(
                "Post state hash not found {} when calculating bonded validators set.",
                root_hash
            );
            logging::log_error(&error_message);
            let mut commit_response = ipc::CommitResponse::new();
            let mut err = ipc::PostEffectsError::new();
            err.set_message(error_message);
            commit_response.set_failed_transform(err);
            commit_response
        }
        Err(GetBondedValidatorsError::PoSNotFound(key)) => grpc_response_from_commit_result::<H>(
            poststate_hash,
            Ok(CommitResult::KeyNotFound(key)),
        ),
    }
}

// Helper method which returns single DeployResult that is set to be a
// WasmError.
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
