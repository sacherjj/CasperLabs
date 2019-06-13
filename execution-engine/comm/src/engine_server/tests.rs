use grpc::RequestOptions;
use parity_wasm::builder::ModuleBuilder;
use parity_wasm::elements::{MemorySection, MemoryType, Module, Section, Serialize};

use engine_server::ipc::{
    CommitRequest, Deploy, DeployCode, ExecRequest, QueryRequest, ValidateRequest,
};
use engine_server::ipc_grpc::ExecutionEngineService;
use engine_server::state::{Key, Key_Address, ProtocolVersion};
use shared::logging::log_level::LogLevel;
use shared::logging::logger::initialize_buffered_logger;
use shared::logging::logger::{LogBufferProvider, BUFFERED_LOGGER};
use storage::global_state::in_memory::InMemoryGlobalState;

use super::super::*;

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
        let mut key = Key::new();
        let mut key_address = Key_Address::new();
        key_address.set_account(MOCKED_ACCOUNT_ADDRESS.to_vec());
        key.set_address(key_address);

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

    let wasm_bytes = get_wasm_bytes();

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
    protocol_version.set_value(1);
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
    deploy_code.set_code(get_wasm_bytes());
    deploy.set_session(deploy_code);
    deploy
}

fn get_wasm_bytes() -> Vec<u8> {
    let mem_section = MemorySection::with_entries(vec![MemoryType::new(16, Some(64))]);
    let section = Section::Memory(mem_section);
    let parity_module: Module = ModuleBuilder::new().with_section(section).build();
    let mut wasm_bytes = vec![];
    parity_module.serialize(&mut wasm_bytes).unwrap();
    wasm_bytes
}
