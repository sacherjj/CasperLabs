use std::collections::btree_map::BTreeMap;

use serde::Serialize;
use slog::{Drain, Level, Logger};

use crate::logging::log_level::LogLevel;
use crate::logging::log_message::LogMessage;
use crate::logging::log_settings::{LogLevelFilter, LogSettings};

pub mod log_level;
pub mod log_message;
pub mod log_settings;

// log with basic stir message
pub fn log(log_settings: &LogSettings, log_level: LogLevel, value: &str) {
    let log_output_factory = |_: &slog::Record| {
        get_msg(
            LogMessage::new_msg(log_settings.to_owned(), log_level, value.to_owned()),
            false,
        )
    };

    slog_log(log_level, log_settings.log_level_filter, log_output_factory);
}

// log with anything Into<LogMessage>
pub fn log_graph<T>(value: T)
where
    T: Into<LogMessage>,
{
    let log_message = value.into();

    let log_settings = log_settings::LogSettings::from(&log_message);

    let log_output_factory = |_: &slog::Record| get_msg(log_message.to_owned(), false);

    slog_log(
        log_message.level,
        log_settings.log_level_filter,
        log_output_factory,
    );
}

// log with message format and properties
pub fn log_props(
    log_settings: &LogSettings,
    log_level: LogLevel,
    message_format: String,
    properties: BTreeMap<String, String>,
) {
    let log_level_filter = log_settings.log_level_filter;

    let log_output_factory = move |_: &slog::Record| {
        get_msg(
            LogMessage::new_props(
                log_settings.to_owned(),
                log_level,
                message_format.to_owned(),
                properties.to_owned(),
            ),
            false,
        )
    };

    slog_log(log_level, log_level_filter, log_output_factory);
}

/// serializes value to json;
/// pretty_print: false = inline
/// pretty_print: true  = pretty printed / multiline
fn get_msg<T>(value: T, pretty_print: bool) -> String
where
    T: Serialize,
{
    if pretty_print {
        match serde_json::to_string_pretty(&value) {
            Ok(json) => json,
            Err(_) => "{\"error\": \"encountered error serializing value\"}".to_owned(),
        }
    } else {
        match serde_json::to_string(&value) {
            Ok(json) => json,
            Err(_) => "{\"error\": \"encountered error serializing value\"}".to_owned(),
        }
    }
}

/// factory method to build and return a slog logger configured as a async terminal writer
fn get_logger(slog_level_filter: Level) -> slog::Logger {
    // temporal dependencies herein; order of nesting drains and filters matters

    let decorator = slog_term::TermDecorator::new().build();

    let drain = slog_term::FullFormat::new(decorator).build().fuse();

    // this is a bit twisted; setting the level filter moves the drain;
    // it is necessary to call .fuse() on it to allow the composition to continue
    let filter = slog::LevelFilter::new(drain, slog_level_filter).fuse();

    // the async drain ideally is the outermost drain
    let drain = slog_async::Async::new(filter).build().fuse();

    Logger::root(drain.fuse(), o!())
}

fn slog_log<F>(
    log_level: log_level::LogLevel,
    log_level_filter: LogLevelFilter,
    log_output_factory: F,
) where
    // The closure takes no input and returns nothing.
    F: Fn(&slog::Record) -> String,
{
    // this formulation and using the level based macros
    // allows slog to defer execution if log level is below filter level
    let fnv = slog::FnValue(log_output_factory);

    let slog_level_filter = log_level_filter.into();

    let logger = get_logger(slog_level_filter);

    let slog_level = log_level.into();

    match slog_level {
        Level::Critical => {
            crit!(logger, "{}", log_level.get_priority(); "json" => fnv);
        }
        Level::Error => {
            error!(logger, "{}", log_level.get_priority(); "json" => fnv);
        }
        Level::Warning => {
            warn!(logger, "{}", log_level.get_priority(); "json" => fnv);
        }
        Level::Info => {
            info!(logger, "{}", log_level.get_priority(); "json" => fnv);
        }
        Level::Trace => {
            trace!(logger, "{}", log_level.get_priority(); "json" => fnv);
        }
        Level::Debug => {
            debug!(logger, "{}", log_level.get_priority(); "json" => fnv);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::logging::log_message::LogMessage;
    use crate::logging::log_settings::LogLevelFilter;
    use std::time::SystemTime;

    const PROC_NAME: &str = "ee-shared-lib-tests";

    //TODO: require an integration test or a custom slog drain to capture log output
    #[test]
    fn should_log_when_level_at_or_above_filter() {
        let settings = LogSettings::new(PROC_NAME, LogLevelFilter::new(LogLevel::Error));

        let log_message = LogMessage::new_msg(
            settings.to_owned(),
            LogLevel::Error,
            "this is a logmessage".to_owned(),
        );

        log_graph(log_message);
    }

    #[test]
    fn should_not_log_when_level_below_filter() {
        let settings = LogSettings::new(PROC_NAME, LogLevelFilter::new(LogLevel::Fatal));

        let log_message = LogMessage::new_msg(
            settings.to_owned(),
            LogLevel::Error,
            "this should not log as the filter is set to Fatal and this message is Error"
                .to_owned(),
        );

        log_graph(log_message);
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

    #[test]
    fn should_log_from_tuple() {
        let x = property_logger_test_helper();

        log_graph(x);
    }

    fn property_logger_test_helper() -> (LogSettings, LogLevel, String, BTreeMap<String, String>) {
        let mut properties: BTreeMap<String, String> = BTreeMap::new();

        properties.insert(
            "entry_point".to_string(),
            "should_log_with_props_and_template".to_string(),
        );

        use std::time::Duration;

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
