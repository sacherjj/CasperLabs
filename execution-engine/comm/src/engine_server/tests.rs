use grpc::RequestOptions;

use shared::init::mocked_account;
use shared::logging::log_level::LogLevel;
use shared::logging::log_settings::{self, LogLevelFilter, LogSettings};
use shared::logging::logger::initialize_buffered_logger;
use shared::logging::logger::{LogBufferProvider, BUFFERED_LOGGER};
use shared::newtypes::CorrelationId;
use shared::test_utils;
use storage::global_state::in_memory::InMemoryGlobalState;

use engine_server;
use engine_server::ipc::{
    CommitRequest, Deploy, DeployCode, ExecRequest, GenesisRequest, KeyAddress, ProtocolVersion,
    QueryRequest, RustBigInt, ValidateRequest,
};
use engine_server::ipc_grpc::ExecutionEngineService;
use execution_engine::engine_state::EngineState;

#[test]
fn should_query_with_metrics() {
    setup();
    let correlation_id = CorrelationId::new();
    let mocked_account = mocked_account(MOCKED_ACCOUNT_ADDRESS);
    let global_state = InMemoryGlobalState::from_pairs(correlation_id, &mocked_account).unwrap();
    let root_hash = global_state.root_hash.to_vec();
    let engine_state = EngineState::new(global_state, false);

    let mut query_request = QueryRequest::new();
    {
        let mut key = engine_server::ipc::Key::new();
        let mut key_address = KeyAddress::new();
        key_address.set_account(MOCKED_ACCOUNT_ADDRESS.to_vec());
        key.set_account(key_address);

        query_request.set_base_key(key);
        query_request.set_path(vec![].into());
        query_request.set_state_hash(root_hash);
    }

    let _query_response_result = engine_state
        .query(RequestOptions::new(), query_request)
        .wait_drop_metadata();

    let log_items = BUFFERED_LOGGER
        .extract_correlated(&correlation_id.to_string())
        .expect("log items expected");

    for log_item in log_items {
        assert!(
            log_item
                .properties
                .contains_key(&"correlation_id".to_string()),
            "should have correlation_id"
        );

        let matched_correlation_id = log_item
            .properties
            .get(&"correlation_id".to_string())
            .expect("should have correlation id value");

        assert_eq!(
            matched_correlation_id,
            &correlation_id.to_string(),
            "correlation_id should match"
        );

        assert_eq!(log_item.log_level, "Metric", "expected Metric");
    }
}

#[test]
fn should_exec_with_metrics() {
    setup();
    let correlation_id = CorrelationId::new();
    let mocked_account = mocked_account(MOCKED_ACCOUNT_ADDRESS);
    let global_state = InMemoryGlobalState::from_pairs(correlation_id, &mocked_account).unwrap();
    let root_hash = global_state.root_hash.to_vec();
    let engine_state = EngineState::new(global_state, false);

    let mut exec_request = ExecRequest::new();
    {
        let mut deploys: protobuf::RepeatedField<Deploy> = <protobuf::RepeatedField<Deploy>>::new();
        deploys.push(get_mock_deploy());

        exec_request.set_deploys(deploys);
        exec_request.set_parent_state_hash(root_hash);
        exec_request.set_protocol_version(get_protocol_version());
    }

    let _exec_response_result = engine_state
        .exec(RequestOptions::new(), exec_request)
        .wait_drop_metadata();

    let log_items = BUFFERED_LOGGER
        .extract_correlated(&correlation_id.to_string())
        .expect("log items expected");

    for log_item in log_items {
        assert!(
            log_item
                .properties
                .contains_key(&"correlation_id".to_string()),
            "should have correlation_id"
        );

        let matched_correlation_id = log_item
            .properties
            .get(&"correlation_id".to_string())
            .expect("should have correlation id value");

        assert_eq!(
            matched_correlation_id,
            &correlation_id.to_string(),
            "correlation_id should match"
        );

        assert_eq!(log_item.log_level, "Metric", "expected Metric");
    }
}

#[test]
fn should_commit_with_metrics() {
    setup();
    let correlation_id = CorrelationId::new();
    let mocked_account = mocked_account(MOCKED_ACCOUNT_ADDRESS);
    let global_state = InMemoryGlobalState::from_pairs(correlation_id, &mocked_account).unwrap();
    let root_hash = global_state.root_hash.to_vec();
    let engine_state = EngineState::new(global_state, false);

    let request_options = RequestOptions::new();

    let mut commit_request = CommitRequest::new();

    commit_request.set_effects(vec![].into());
    commit_request.set_prestate_hash(root_hash);

    let _commit_response_result = engine_state
        .commit(request_options, commit_request)
        .wait_drop_metadata();

    let log_items = BUFFERED_LOGGER
        .extract_correlated(&correlation_id.to_string())
        .expect("log items expected");

    for log_item in log_items {
        assert!(
            log_item
                .properties
                .contains_key(&"correlation_id".to_string()),
            "should have correlation_id"
        );

        let matched_correlation_id = log_item
            .properties
            .get(&"correlation_id".to_string())
            .expect("should have correlation id value");

        assert_eq!(
            matched_correlation_id,
            &correlation_id.to_string(),
            "correlation_id should match"
        );

        assert_eq!(log_item.log_level, "Metric", "expected Metric");
    }
}

#[test]
fn should_validate_with_metrics() {
    setup();
    let correlation_id = CorrelationId::new();
    let mocked_account = mocked_account(MOCKED_ACCOUNT_ADDRESS);
    let global_state = InMemoryGlobalState::from_pairs(correlation_id, &mocked_account).unwrap();
    let engine_state = EngineState::new(global_state, false);

    let mut validate_request = ValidateRequest::new();

    let wasm_bytes = test_utils::create_empty_wasm_module_bytes();

    validate_request.set_payment_code(wasm_bytes.clone());
    validate_request.set_session_code(wasm_bytes);

    let _validate_response_result = engine_state
        .validate(RequestOptions::new(), validate_request)
        .wait_drop_metadata();

    let log_items = BUFFERED_LOGGER
        .extract_correlated(&correlation_id.to_string())
        .expect("log items expected");

    for log_item in log_items {
        assert!(
            log_item
                .properties
                .contains_key(&"correlation_id".to_string()),
            "should have correlation_id"
        );

        let matched_correlation_id = log_item
            .properties
            .get(&"correlation_id".to_string())
            .expect("should have correlation id value");

        assert_eq!(
            matched_correlation_id,
            &correlation_id.to_string(),
            "correlation_id should match"
        );

        assert_eq!(log_item.log_level, "Metric", "expected Metric");
    }
}

#[test]
fn should_run_genesis() {
    let global_state = InMemoryGlobalState::empty().expect("should create global state");
    let engine_state = EngineState::new(global_state, false);

    let genesis_request = {
        let genesis_account_addr = [6u8; 32].to_vec();

        let initial_tokens = {
            let mut ret = RustBigInt::new();
            ret.set_bit_width(512);
            ret.set_value("1000000".to_string());
            ret
        };

        let mint_code = {
            let mut ret = DeployCode::new();
            let wasm_bytes = test_utils::create_empty_wasm_module_bytes();
            ret.set_code(wasm_bytes);
            ret
        };

        let proof_of_stake_code = {
            let mut ret = DeployCode::new();
            let wasm_bytes = test_utils::create_empty_wasm_module_bytes();
            ret.set_code(wasm_bytes);
            ret
        };

        let protocol_version = {
            let mut ret = ProtocolVersion::new();
            ret.set_version(1);
            ret
        };

        let mut ret = GenesisRequest::new();
        ret.set_address(genesis_account_addr.to_vec());
        ret.set_initial_tokens(initial_tokens);
        ret.set_mint_code(mint_code);
        ret.set_proof_of_stake_code(proof_of_stake_code);
        ret.set_protocol_version(protocol_version);
        ret
    };

    let request_options = RequestOptions::new();

    let genesis_response = engine_state
        .run_genesis(request_options, genesis_request)
        .wait_drop_metadata();

    let response = genesis_response.unwrap();

    let state_handle = engine_state.state();

    let state_handle_guard = state_handle.lock();

    let state_root_hash = state_handle_guard.root_hash;
    let response_root_hash = response.get_success().get_poststate_hash();

    assert_eq!(state_root_hash.to_vec(), response_root_hash.to_vec());
}

lazy_static! {
    static ref LOG_SETTINGS: LogSettings = get_log_settings(LogLevel::Debug);
}

const PROC_NAME: &str = "ee-shared-lib-tests";
const MOCKED_ACCOUNT_ADDRESS: [u8; 32] = [48u8; 32];

fn setup() {
    initialize_buffered_logger();
    log_settings::set_log_settings_provider(&*LOG_SETTINGS);
}

fn get_log_settings(log_level: LogLevel) -> LogSettings {
    let log_level_filter = LogLevelFilter::new(log_level);

    LogSettings::new(PROC_NAME, log_level_filter)
}

fn get_protocol_version() -> ProtocolVersion {
    let mut protocol_version: ProtocolVersion = ProtocolVersion::new();
    protocol_version.set_version(1);
    protocol_version
}

fn get_mock_deploy() -> Deploy {
    let mut deploy = Deploy::new();
    deploy.set_address(MOCKED_ACCOUNT_ADDRESS.to_vec());
    deploy.set_gas_limit(1000);
    deploy.set_gas_price(1);
    deploy.set_nonce(1);
    deploy.set_timestamp(10);
    let mut deploy_code = DeployCode::new();
    deploy_code.set_code(test_utils::create_empty_wasm_module_bytes());
    deploy.set_session(deploy_code);
    deploy
}
