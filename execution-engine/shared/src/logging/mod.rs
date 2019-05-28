use std::collections::btree_map::BTreeMap;

use crate::logging::log_level::LogLevel;
use crate::logging::log_message::LogMessage;
use crate::logging::utils::jsonify;

pub mod log_level;
pub mod log_message;
pub mod log_settings;
pub mod logger;
pub(crate) mod utils;

/// # Arguments
///
/// * `log_level` - log level of the message to be logged
/// * `log_message` - the message to be logged
pub fn log(log_level: LogLevel, log_message: &str) {
    let log_settings_provider = log_settings::get_log_settings_provider();

    if log_settings_provider.filter(log_level) {
        return;
    }

    logger::LOGGER_INIT.call_once(|| {
        log::set_logger(&logger::TERMINAL_LOGGER).expect("TERMINAL_LOGGER should be set");
        log::set_max_level(log::LevelFilter::Debug);
    });

    let log_message = LogMessage::new_msg(log_settings_provider, log_level, log_message.to_owned());

    let json = jsonify(&log_message, false);

    log::log!(
        log_level.into(),
        "{timestamp} {loglevel} {priority} {hostname} {facility} payload={payload}",
        timestamp = log_message.timestamp,
        loglevel = log_message.log_level.to_uppercase(),
        priority = log_message.priority.value(),
        hostname = log_message.host_name.value(),
        facility = log_message.process_name.value(),
        payload = json
    );
}

/// # Arguments
///
/// * `log_level` - log level of the message to be logged
/// * `message_format` - a message template to apply over properties by key
/// * `properties` - a collection of machine readable key / value properties which will be logged
pub fn log_details(
    log_level: LogLevel,
    message_format: String,
    properties: BTreeMap<String, String>,
) {
    let log_settings_provider = log_settings::get_log_settings_provider();

    if log_settings_provider.filter(log_level) {
        return;
    }

    logger::LOGGER_INIT.call_once(|| {
        log::set_logger(&logger::TERMINAL_LOGGER).expect("TERMINAL_LOGGER should be set");
        log::set_max_level(log::LevelFilter::Debug);
    });

    let log_message = LogMessage::new_props(
        log_settings_provider,
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
        facility = log_message.process_name.value(),
        payload = json
    );
}

/// # Arguments
///
/// * `log_message` - the message to be logged
pub fn log_fatal(log_message: &str) {
    log(LogLevel::Fatal, log_message);
}

/// # Arguments
///
/// * `log_message` - the message to be logged
pub fn log_error(log_message: &str) {
    log(LogLevel::Error, log_message);
}

/// # Arguments
///
/// * `log_message` - the message to be logged
pub fn log_warning(log_message: &str) {
    log(LogLevel::Warning, log_message);
}

/// # Arguments
///
/// * `log_message` - the message to be logged
pub fn log_info(log_message: &str) {
    log(LogLevel::Info, log_message);
}

/// # Arguments
///
/// * `log_message` - the message to be logged
pub fn log_debug(log_message: &str) {
    log(LogLevel::Debug, log_message);
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::logging::log_settings::{
        get_log_settings_provider, set_log_settings_provider, LogLevelFilter, LogSettings,
    };
    use std::thread;
    use std::time::{Duration, SystemTime};

    const PROC_NAME: &str = "ee-shared-lib-tests";

    lazy_static! {
        static ref LOG_SETTINGS: LogSettings = get_log_settings(LogLevel::Info);
    }

    fn get_log_settings(log_level: LogLevel) -> LogSettings {
        let log_level_filter = LogLevelFilter::new(log_level);

        LogSettings::new(PROC_NAME, log_level_filter)
    }

    #[test]
    fn should_log_when_level_at_or_above_filter() {
        log_settings::set_log_settings_provider(&*LOG_SETTINGS);

        log(LogLevel::Error, "this is a logmessage");
    }

    #[test]
    fn should_not_log_when_level_below_filter() {
        log_settings::set_log_settings_provider(&*LOG_SETTINGS);

        log(
            LogLevel::Debug,
            "this should not log as the filter is set to Info and this message is Debug",
        );
    }

    #[test]
    fn should_log_string() {
        log_settings::set_log_settings_provider(&*LOG_SETTINGS);

        let log_message = String::from("this is a string and it should get logged");

        log(LogLevel::Info, &log_message);
    }

    #[test]
    fn should_log_stir() {
        log_settings::set_log_settings_provider(&*LOG_SETTINGS);

        log(
            LogLevel::Info,
            "this is a string slice and it should get logged",
        );
    }

    #[test]
    fn should_log_with_props_and_template() {
        log_settings::set_log_settings_provider(&*LOG_SETTINGS);

        let x = property_logger_test_helper();

        log_details(x.0, x.1, x.2);
    }

    #[test]
    fn should_set_and_get_log_settings_provider() {
        let log_settings = &*LOG_SETTINGS;

        let handle = thread::spawn(move || {
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

    fn property_logger_test_helper() -> (LogLevel, String, BTreeMap<String, String>) {
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
}
