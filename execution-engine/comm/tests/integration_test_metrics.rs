extern crate grpc;
#[macro_use]
extern crate lazy_static;

extern crate execution_engine;
extern crate shared;
extern crate storage;

extern crate casperlabs_engine_grpc_server;

#[allow(unused)]
mod test_support;

use grpc::RequestOptions;

use execution_engine::engine_state::EngineState;
use shared::init::mocked_account;
use shared::logging::log_level::LogLevel;
use shared::logging::log_settings::{self, LogLevelFilter, LogSettings};
use shared::logging::logger::{self, LogBufferProvider, BUFFERED_LOGGER};
use shared::newtypes::CorrelationId;
use shared::test_utils;
use storage::global_state::in_memory::InMemoryGlobalState;

use casperlabs_engine_grpc_server::engine_server::ipc::{
    CommitRequest, Deploy, ExecRequest, QueryRequest, ValidateRequest,
};
use casperlabs_engine_grpc_server::engine_server::ipc_grpc::ExecutionEngineService;
use casperlabs_engine_grpc_server::engine_server::state::{Key, Key_Address};

pub const PROC_NAME: &str = "ee-shared-lib-tests";

pub fn get_log_settings(log_level: LogLevel) -> LogSettings {
    let log_level_filter = LogLevelFilter::new(log_level);
    LogSettings::new(PROC_NAME, log_level_filter)
}

fn setup() {
    logger::initialize_buffered_logger();
    log_settings::set_log_settings_provider(&*LOG_SETTINGS);
}

lazy_static! {
    static ref LOG_SETTINGS: LogSettings = get_log_settings(LogLevel::Debug);
}

#[test]
fn should_query_with_metrics() {
    setup();
    let correlation_id = CorrelationId::new();
    let mocked_account = mocked_account(test_support::MOCKED_ACCOUNT_ADDRESS);
    let global_state = InMemoryGlobalState::from_pairs(correlation_id, &mocked_account).unwrap();
    let root_hash = global_state.root_hash.to_vec();
    let engine_state = EngineState::new(global_state);

    let mut query_request = QueryRequest::new();
    {
        let mut key = Key::new();
        let mut key_address = Key_Address::new();
        key_address.set_account(test_support::MOCKED_ACCOUNT_ADDRESS.to_vec());
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
    let mocked_account = mocked_account(test_support::MOCKED_ACCOUNT_ADDRESS);
    let global_state = InMemoryGlobalState::from_pairs(correlation_id, &mocked_account).unwrap();
    let root_hash = global_state.root_hash.to_vec();
    let engine_state = EngineState::new(global_state);

    let mut exec_request = ExecRequest::new();
    {
        let mut deploys: protobuf::RepeatedField<Deploy> = <protobuf::RepeatedField<Deploy>>::new();
        deploys.push(test_support::get_mock_deploy());

        exec_request.set_deploys(deploys);
        exec_request.set_parent_state_hash(root_hash);
        exec_request.set_protocol_version(test_support::get_protocol_version());
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
    let mocked_account = mocked_account(test_support::MOCKED_ACCOUNT_ADDRESS);
    let global_state = InMemoryGlobalState::from_pairs(correlation_id, &mocked_account).unwrap();
    let root_hash = global_state.root_hash.to_vec();
    let engine_state = EngineState::new(global_state);

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
    let mocked_account = mocked_account(test_support::MOCKED_ACCOUNT_ADDRESS);
    let global_state = InMemoryGlobalState::from_pairs(correlation_id, &mocked_account).unwrap();
    let engine_state = EngineState::new(global_state);

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
