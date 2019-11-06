use lazy_static::lazy_static;

use contract_ffi::key::Key;
use engine_core::engine_state::EngineConfig;
use engine_shared::logging::log_level::LogLevel;
use engine_shared::logging::log_settings::{self, LogLevelFilter, LogSettings};
use engine_shared::logging::logger::{self, LogBufferProvider, BUFFERED_LOGGER};
use engine_shared::newtypes::CorrelationId;
use engine_shared::test_utils;
use engine_storage::global_state::in_memory::InMemoryGlobalState;

use crate::support::test_support::{self, InMemoryWasmTestBuilder};

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
    let mocked_account = test_utils::mocked_account(test_support::MOCKED_ACCOUNT_ADDRESS);
    let (global_state, root_hash) =
        InMemoryGlobalState::from_pairs(correlation_id, &mocked_account).unwrap();
    let engine_config = EngineConfig::new();
    let result =
        InMemoryWasmTestBuilder::new(global_state, engine_config, root_hash.to_vec()).finish();

    let _result = result
        .builder()
        .query(
            None,
            Key::Account(test_support::MOCKED_ACCOUNT_ADDRESS),
            &[],
        )
        .expect("should query");

    let log_items = BUFFERED_LOGGER
        .extract_correlated(&correlation_id.to_string())
        .expect("log items expected");

    assert!(!log_items.is_empty());
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
    let mocked_account = test_utils::mocked_account(test_support::MOCKED_ACCOUNT_ADDRESS);
    let (global_state, root_hash) =
        InMemoryGlobalState::from_pairs(correlation_id, &mocked_account).unwrap();

    let engine_config = EngineConfig::new();

    let result =
        InMemoryWasmTestBuilder::new(global_state, engine_config, root_hash.to_vec()).finish();

    let _commit_response = result
        .builder()
        .commit_transforms(root_hash.to_vec(), Default::default());

    let log_items = BUFFERED_LOGGER
        .extract_correlated(&correlation_id.to_string())
        .expect("log items expected");

    assert!(!log_items.is_empty());
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
    let mocked_account = test_utils::mocked_account(test_support::MOCKED_ACCOUNT_ADDRESS);
    let (global_state, root_hash) =
        InMemoryGlobalState::from_pairs(correlation_id, &mocked_account).unwrap();
    let engine_config = EngineConfig::new();

    let wasm_bytes = test_utils::create_empty_wasm_module_bytes();

    let result =
        InMemoryWasmTestBuilder::new(global_state, engine_config, root_hash.to_vec()).finish();

    let _validate_response = result.builder().validate(wasm_bytes);

    let log_items = BUFFERED_LOGGER
        .extract_correlated(&correlation_id.to_string())
        .expect("log items expected");
    assert!(!log_items.is_empty());
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
