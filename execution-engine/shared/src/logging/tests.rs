use std::thread;
use std::time::{Instant, SystemTime};

use crate::logging::log_settings::{
    get_log_settings_provider, set_log_settings_provider, LogLevelFilter, LogSettings,
};
use crate::logging::logger::{initialize_buffered_logger, LogBufferProvider};

use super::*;

const PROC_NAME: &str = "ee-shared-lib-tests";

lazy_static! {
    static ref LOG_SETTINGS: LogSettings = get_log_settings(LogLevel::Debug);
}

fn get_log_settings(log_level: LogLevel) -> LogSettings {
    let log_level_filter = LogLevelFilter::new(log_level);

    LogSettings::new(PROC_NAME, log_level_filter)
}

fn setup() {
    initialize_buffered_logger();
    log_settings::set_log_settings_provider(&*LOG_SETTINGS);
}

fn property_logger_test_helper() -> (LogLevel, String, BTreeMap<String, String>) {
    let mut properties: BTreeMap<String, String> = BTreeMap::new();

    properties.insert(
        "entry_point".to_string(),
        "should_log_with_props_and_template".to_string(),
    );

    let start = Instant::now();

    let utc = chrono::DateTime::<chrono::Utc>::from(SystemTime::now());

    properties.insert("start".to_string(), format!("{:?}", utc));

    let success = true;

    // simulate logging misc other data items
    properties.insert("some-flag".to_string(), format!("{flag}", flag = 0));
    properties.insert("some-code".to_string(), "XYZ".to_string());
    properties.insert(
        "some-metric".to_string(),
        format!("x: {x}|y: {y}", x = 15, y = 10),
    );
    properties.insert(
        "duration-in-nanoseconds".to_string(),
        format!("{:?}", start.elapsed().as_nanos()),
    );

    properties.insert("successful".to_string(), success.to_string());

    let utc = chrono::DateTime::<chrono::Utc>::from(SystemTime::now());

    properties.insert("stop".to_string(), format!("{:?}", utc));

    // arbitrary format; any {???} brace encased elements that match a property key will get transcluded in description
    let mut message_format = String::new();

    message_format.push_str(
        "TRACE: {entry_point} start: {start}; stop: {stop}; \
         elapsed(ns): {duration-in-nanoseconds}; successful: {successful}",
    );

    if properties.contains_key("error") {
        message_format.push_str("; error: {error}");
    }

    (LogLevel::Info, message_format, properties)
}

#[test]
fn log_duration_should_log_correlation_id() {
    setup();

    let correlation_id = CorrelationId::new();

    let start = SystemTime::now();

    let duration = if let Ok(duration) = start.elapsed() {
        duration
    } else {
        panic!("could not get duration")
    };

    log_duration(correlation_id, "duration_seconds", "test", duration);

    let log_items = logger::BUFFERED_LOGGER
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
fn should_log_when_level_at_or_above_filter() {
    setup();

    let message_id = log(LogLevel::Error, "this is a logmessage").expect("expected message_id");

    let message = logger::BUFFERED_LOGGER
        .extract(&message_id.value())
        .expect("expected message");

    assert_eq!(message.log_level, "Error", "expected Error");
}

#[test]
fn should_log_string() {
    setup();

    let log_message = String::from("this is a string and it should get logged");

    let message_id = log(LogLevel::Info, &log_message).expect("expected message_id");

    let message = logger::BUFFERED_LOGGER
        .extract(&message_id.value())
        .expect("expected message");

    assert_eq!(message.log_level, "Info", "expected Info");
}

#[test]
fn should_log_stir() {
    setup();

    let message_id = log(
        LogLevel::Info,
        "this is a string slice and it should get logged",
    )
    .expect("should have logged");

    let item = logger::BUFFERED_LOGGER
        .extract(&message_id.value())
        .expect("missing item by message_id");

    assert_eq!(item.log_level, "Info", "expected Info");
}

#[test]
fn should_log_with_props_and_template() {
    setup();

    let x = property_logger_test_helper();

    let message_id = log_details(x.0, x.1, x.2).expect("expected details");

    let message = logger::BUFFERED_LOGGER
        .extract(&message_id.value())
        .expect("expected message");

    let some_flag = message
        .properties
        .get(&"some-flag".to_string())
        .expect("should have some flag");

    assert_eq!(some_flag, &"0".to_string(), "should match");

    let some_metric = message
        .properties
        .get(&"some-metric".to_string())
        .expect("should have some metric");

    assert_eq!(some_metric, &"x: 15|y: 10".to_string(), "should match");

    let some_code = message
        .properties
        .get(&"some-code".to_string())
        .expect("should have some code");

    assert_eq!(some_code, &"XYZ".to_string(), "should match");

    assert_eq!(message.log_level, "Info", "expected Info")
}

#[test]
fn should_set_and_get_log_settings_provider() {
    setup();

    let handle = thread::spawn(move || {
        let log_settings = &*LOG_SETTINGS;
        set_log_settings_provider(log_settings);

        let log_settings_provider = get_log_settings_provider();

        assert_eq!(
            &log_settings.process_id,
            &log_settings_provider.get_process_id(),
            "process_id should be the same",
        );

        assert_eq!(
            &log_settings.host_name,
            &log_settings_provider.get_host_name(),
            "host_name should be the same",
        );

        assert_eq!(
            &log_settings.process_name,
            &log_settings_provider.get_process_name(),
            "process_name should be the same",
        );

        assert_eq!(
            &log_settings.log_level_filter,
            &log_settings_provider.get_log_level_filter(),
            "log_level_filter should be the same",
        );
    });

    let _r = handle.join();
}
