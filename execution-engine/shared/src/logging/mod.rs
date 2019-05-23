use std::collections::btree_map::BTreeMap;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use crate::logging::log_level::LogLevel;
use crate::logging::log_message::{LogMessage, MessageId};
use crate::logging::logger::set_terminal_logger;
use crate::newtypes::CorrelationId;
use crate::utils::jsonify;

pub mod log_level;
pub mod log_message;
pub mod log_settings;
#[macro_use]
pub mod logger;

/// # Arguments
///
/// * `log_level` - log level of the message to be logged
/// * `log_message` - the message to be logged
pub fn log(log_level: LogLevel, log_message: &str) -> Option<MessageId> {
    set_terminal_logger();
    let log_settings_provider = log_settings::get_log_settings_provider();

    if log_settings_provider.filter(log_level) {
        return None;
    }

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

    Some(log_message.message_id)
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
) -> Option<MessageId> {
    set_terminal_logger();
    let log_settings_provider = log_settings::get_log_settings_provider();

    if log_settings_provider.filter(log_level) {
        return None;
    }

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

    Some(log_message.message_id)
}

/// # Arguments
///
/// * `correlation_id` - a shared identifier used to group metrics
/// * `metric` - the name of the metric
/// * `tag` - a grouping tag for the metric
/// * `duration` - in seconds
pub fn log_duration(
    correlation_id: CorrelationId,
    metric: &str,
    tag: &str,
    duration: Duration,
) -> Option<MessageId> {
    set_terminal_logger();
    let duration_in_seconds: f64 = duration.as_float_secs();

    log_metric(
        correlation_id,
        metric,
        tag,
        "duration_in_seconds",
        duration_in_seconds,
    )
}

/// # Arguments
///
/// * `correlation_id` - a shared identifier used to group metrics
/// * `metric` - the name of the metric
/// * `tag` - a grouping tag for the metric
/// * `metric_key` - property key for metric's value
/// * `metric_value` - numeric value of metric
pub fn log_metric(
    correlation_id: CorrelationId,
    metric: &str,
    tag: &str,
    metric_key: &str,
    metric_value: f64,
) -> Option<MessageId> {
    set_terminal_logger();
    let log_settings_provider = log_settings::get_log_settings_provider();

    const METRIC_LOG_LEVEL: LogLevel = LogLevel::Metric;

    if log_settings_provider.filter(METRIC_LOG_LEVEL) {
        return None;
    }

    let mut properties: BTreeMap<String, String> = BTreeMap::new();

    let from_epoch = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .expect("UNIX EPOCH ERROR");

    let milliseconds_since_epoch = from_epoch.as_millis() as i64;

    // https://prometheus.io/docs/instrumenting/exposition_formats/
    let tsd_metric = format!(
        "{}{{tag=\"{}\", correlation_id=\"{}\"}} {} {:?}",
        metric,
        tag,
        correlation_id.to_string(),
        metric_value,
        milliseconds_since_epoch
    );

    properties.insert("correlation_id".to_string(), correlation_id.to_string());

    properties.insert("time-series-data".to_string(), tsd_metric);

    properties.insert(metric_key.to_string(), format!("{:?}", metric_value));

    properties.insert(
        "message".to_string(),
        format!("{} {} {}", metric, tag, metric_value),
    );

    let message_format = String::from("{message}");

    log_details(METRIC_LOG_LEVEL, message_format, properties)
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

/// this macro does not support early return
#[macro_export]
macro_rules! capture_duration {
    ($correlation_id:expr, $metric_name:expr, $tag:expr, $f:expr) => {{
        let log_settings_provider = $crate::logging::log_settings::get_log_settings_provider();

        if log_settings_provider.filter($crate::logging::log_level::LogLevel::Metric) {
            $f
        } else {
            let start = SystemTime::now();

            let result = $f;

            $crate::logging::log_duration(
                $correlation_id,
                $metric_name,
                $tag,
                start.elapsed().unwrap(),
            );

            result
        }
    }};
}

/// this macro logs elapsed time and returns the elapsed duration if log settings allow metrics, otherwise None.
#[macro_export]
macro_rules! capture_elapsed {
    ($correlation_id:expr, $metric_name:expr, $tag:expr, $s:expr) => {{
        let log_settings_provider = $crate::logging::log_settings::get_log_settings_provider();

        if log_settings_provider.filter($crate::logging::log_level::LogLevel::Metric) {
            None
        } else {
            let elapsed = $s.elapsed().unwrap();

            $crate::logging::log_duration($correlation_id, $metric_name, $tag, elapsed);

            Some(elapsed)
        }
    }};
}

/// this macro logs numerical gauge if log settings allow metrics
#[macro_export]
macro_rules! capture_gauge {
    ($correlation_id:expr, $metric_name:expr, $tag:expr, $metric:expr) => {{
        let log_settings_provider = $crate::logging::log_settings::get_log_settings_provider();

        if !log_settings_provider.filter($crate::logging::log_level::LogLevel::Metric) {
            $crate::logging::log_metric($correlation_id, $metric_name, $tag, "gauge", $metric);
        }
    }};
}

#[cfg(test)]
mod tests;
