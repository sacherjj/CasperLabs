use std::collections::btree_map::BTreeMap;

use crate::logging::log_level::LogLevel;
use crate::logging::log_message::LogMessage;
use crate::logging::log_settings::LogSettings;
use crate::logging::utils::jsonify;

pub mod log_level;
pub mod log_message;
pub mod log_settings;
pub mod logger;
pub(crate) mod utils;

// log with simple stir message
pub fn log(log_settings: &LogSettings, log_level: LogLevel, value: &str) {
    if log_settings.filter(log_level) {
        return;
    }

    logger::LOGGER_INIT.call_once(|| {
        log::set_logger(&logger::TERMINAL_LOGGER).expect("TERMINAL_LOGGER should be set");
        log::set_max_level(log::LevelFilter::Trace);
    });

    let log_message = LogMessage::new_msg(log_settings.to_owned(), log_level, value.to_owned());

    let json = jsonify(&log_message, false);

    log::log!(
        log_level.into(),
        "{timestamp} {loglevel} {priority} {hostname} {facility} payload={payload}",
        timestamp = log_message.timestamp,
        loglevel = log_message.log_level.to_uppercase(),
        priority = log_message.priority.value(),
        hostname = log_message.host_name.value(),
        facility = log_message.process_name.snake_case(),
        payload = json
    );
}

// log with message format and properties
pub fn log_props(
    log_settings: &LogSettings,
    log_level: LogLevel,
    message_format: String,
    properties: BTreeMap<String, String>,
) {
    if log_settings.filter(log_level) {
        return;
    }

    logger::LOGGER_INIT.call_once(|| {
        log::set_logger(&logger::TERMINAL_LOGGER).expect("TERMINAL_LOGGER should be set");
        log::set_max_level(log::LevelFilter::Trace);
    });

    let log_message = LogMessage::new_props(
        log_settings.to_owned(),
        log_level,
        message_format.to_owned(),
        properties.to_owned(),
    );

    let json = jsonify(&log_message, false);

    log::log!(
        log_level.into(),
        "{timestamp} {loglevel} {priority} {hostname} {facility} payload={payload}",
        timestamp = log_message.timestamp,
        loglevel = log_message.log_level.to_uppercase(),
        priority = log_message.priority.value(),
        hostname = log_message.host_name.value(),
        facility = log_message.process_name.snake_case(),
        payload = json
    );
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::logging::log_settings::LogLevelFilter;
    use std::time::{Duration, SystemTime};

    const PROC_NAME: &str = "ee-shared-lib-tests";

    //TODO: require an integration test or a custom slog drain to capture log output
    #[test]
    fn should_log_when_level_at_or_above_filter() {
        let log_settings = LogSettings::new(PROC_NAME, LogLevelFilter::new(LogLevel::Error));

        log(&log_settings, LogLevel::Error, "this is a logmessage");
    }

    #[test]
    fn should_not_log_when_level_below_filter() {
        let log_settings = LogSettings::new(PROC_NAME, LogLevelFilter::new(LogLevel::Fatal));

        log(
            &log_settings,
            LogLevel::Error,
            "this should not log as the filter is set to Fatal and this message is Error",
        );
    }

    #[test]
    fn should_log_stir() {
        let settings = LogSettings::new(PROC_NAME, LogLevelFilter::new(LogLevel::Debug));
        log(
            &settings,
            LogLevel::Debug,
            "this is a stir and it should get logged",
        );
    }

    #[test]
    fn should_log_with_props_and_template() {
        let x = property_logger_test_helper();

        log_props(&x.0, x.1, x.2, x.3);
    }

    fn property_logger_test_helper() -> (LogSettings, LogLevel, String, BTreeMap<String, String>) {
        let mut properties: BTreeMap<String, String> = BTreeMap::new();

        properties.insert(
            "entry_point".to_string(),
            "should_log_with_props_and_template".to_string(),
        );

        let start = SystemTime::now();

        let utc = chrono::DateTime::<chrono::Utc>::from(start);

        properties.insert("start".to_string(), format!("{:?}", utc));

        let mut success = false;

        let mut o: Option<Duration> = None;

        if let Ok(elapsed) = start.elapsed() {
            o = Some(elapsed);

            // simulate logging misc other data items
            properties.insert("some-flag".to_string(), format!("{flag}", flag = 0));
            properties.insert("some-code".to_string(), "XYZ".to_string());
            properties.insert(
                "some-metric".to_string(),
                format!("x: {x}|y: {y}", x = 15, y = 10),
            );

            success = true;
        } else {
            let end = SystemTime::now();
            match end.duration_since(start) {
                Ok(d) => o = Some(d),
                Err(e) => {
                    properties.insert("error".to_string(), format!("{:?}", e));
                }
            }
        }

        if let Some(duration) = o {
            properties.insert(
                "duration-in-nanoseconds".to_string(),
                format!("{:?}", duration.as_nanos()),
            );
        }

        properties.insert("successful".to_string(), success.to_string());

        let utc = chrono::DateTime::<chrono::Utc>::from(SystemTime::now());

        properties.insert("stop".to_string(), format!("{:?}", utc));

        let settings = LogSettings::new(PROC_NAME, LogLevelFilter::new(LogLevel::Debug));

        // arbitrary format; any {???} brace encased elements that match a property key will get transcluded in description
        let mut message_format = String::new();

        message_format.push_str(
            "TRACE: {entry_point} start: {start}; stop: {stop}; \
             elapsed(ns): {duration-in-nanoseconds}; successful: {successful}",
        );

        if properties.contains_key("error") {
            message_format.push_str("; error: {error}");
        }

        (settings, LogLevel::Info, message_format, properties)
    }
}
