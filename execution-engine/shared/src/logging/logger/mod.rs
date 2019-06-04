use std::collections::BTreeMap;
use std::sync::{Mutex, Once};

use log::{Metadata, Record};
use serde::Deserialize;
use serde::Serialize;

pub(crate) static LOGGER_INIT: Once = Once::new();
pub(crate) const LOG_MAX_LEVEL: log::LevelFilter = log::LevelFilter::Trace;
pub(crate) const LOGGER_EXPECT: &str = "Logger should be set";

/// log lines are written to StdOut
pub(crate) static TERMINAL_LOGGER: TerminalLogger = TerminalLogger;

pub struct BufferedLogger {
    queue: Mutex<BTreeMap<String, LogLineItem>>,
}

impl log::Log for BufferedLogger {
    fn enabled(&self, metadata: &Metadata) -> bool {
        metadata.target().starts_with("shared::logging") && metadata.level() <= log::Level::Trace
    }

    fn log(&self, record: &Record) {
        let metadata = record.metadata();

        // self.enabled caused 'unresolved ref' error
        if Self::enabled(&self, metadata) {
            let line = format!("{}", record.args());

            if let Some(idx) = line.find("payload=") {
                let start = idx + 8;
                let end = line.len();
                let slice = &line[start..end];
                if let Ok(log_line_item) = LogLineItem::from_log_line(slice) {
                    self.push(log_line_item);
                }
            }
        }
    }

    fn flush(&self) {
        self.reset();
    }
}

pub fn get_buffered_logger() -> BufferedLogger {
    BufferedLogger {
        queue: Mutex::new(BTreeMap::new()),
    }
}

lazy_static! {
    /// log lines buffered internally instead of being output.
    /// log lines can be retrieved via LogBufferProvider
    pub static ref BUFFERED_LOGGER: BufferedLogger = get_buffered_logger();
}

pub struct TerminalLogger;

impl log::Log for TerminalLogger {
    fn enabled(&self, metadata: &Metadata) -> bool {
        metadata.target().starts_with("shared::logging") && metadata.level() <= log::Level::Trace
    }

    fn log(&self, record: &Record) {
        let metadata = record.metadata();

        // self.enabled caused 'unresolved ref' error
        if Self::enabled(&self, metadata) {
            println!("{}", record.args());
        }
    }

    fn flush(&self) {}
}

pub trait LogBufferProvider {
    fn push(&self, line: LogLineItem);
    fn extract(&self, message_id: &str) -> Option<LogLineItem>;
    fn extract_correlated(&self, correlation_id: &str) -> Option<Vec<LogLineItem>>;
    fn drain(&self) -> Vec<LogLineItem>;
    fn reset(&self);
}

impl LogBufferProvider for BufferedLogger {
    // add item to buffer
    fn push(&self, line: LogLineItem) {
        let key = line.message_id.clone();
        let value = line;

        if let Ok(mut guard) = self.queue.lock() {
            guard.insert(key, value);
        };
    }

    // remove and return item from buffer by matching message_id
    fn extract(&self, message_id: &str) -> Option<LogLineItem> {
        if let Ok(mut guard) = self.queue.lock() {
            if !guard.contains_key(message_id) {
                return None;
            }

            return guard.remove(message_id);
        }

        None
    }

    // remove and return all items with matching correlation_id
    fn extract_correlated(&self, correlation_id: &str) -> Option<Vec<LogLineItem>> {
        const CORRELATION_ID_KEY: &str = "correlation_id";
        let mut result: Vec<LogLineItem> = vec![];
        if let Ok(mut guard) = self.queue.lock() {
            let keys: Vec<_> = guard.keys().cloned().collect();

            for key in keys {
                if let Some(value) = guard.get(&key) {
                    if value.properties.contains_key(CORRELATION_ID_KEY) {
                        if let Some(c) = value.properties.get(CORRELATION_ID_KEY) {
                            if c == correlation_id {
                                if let Some(value) = guard.remove(&key) {
                                    result.push(value)
                                }
                            }
                        }
                    }
                }
            }

            return Some(result);
        }
        None
    }

    // remove and return all items in buffer
    fn drain(&self) -> Vec<LogLineItem> {
        let mut result: Vec<LogLineItem> = vec![];

        if let Ok(mut guard) = self.queue.lock() {
            let keys: Vec<_> = guard.keys().cloned().collect();
            for key in keys {
                if let Some(value) = guard.remove(&key) {
                    result.push(value)
                }
            }

            guard.clear();
        };

        result
    }

    // empty the buffer
    fn reset(&self) {
        if let Ok(mut guard) = self.queue.lock() {
            guard.clear();
        };
    }
}

/// container for LogLineItem data
#[derive(Clone, Debug, Default, Hash, PartialEq, Serialize, Deserialize)]
pub struct LogLineItem {
    pub timestamp: String,
    pub process_id: i64,
    pub process_name: String,
    pub host_name: String,
    pub log_level: String,
    pub priority: i64,
    pub message_type: String,
    pub message_type_version: String,
    pub message_id: String,
    pub description: String,
    pub properties: BTreeMap<String, String>,
}

impl LogLineItem {
    pub fn from_log_line(line: &str) -> Result<LogLineItem, serde_json::Error> {
        serde_json::from_str::<LogLineItem>(line)
    }
}

/// set terminal logger as application logger
pub fn initialize_terminal_logger() {
    LOGGER_INIT.call_once(|| {
        log::set_logger(&TERMINAL_LOGGER).expect(LOGGER_EXPECT);
        log::set_max_level(LOG_MAX_LEVEL);
    });
}

/// set buffered logger as application logger
pub fn initialize_buffered_logger() {
    LOGGER_INIT.call_once(|| {
        log::set_logger(&*BUFFERED_LOGGER).expect(LOGGER_EXPECT);
        log::set_max_level(LOG_MAX_LEVEL);
    });
}

#[cfg(test)]
mod tests {
    use std::sync::Arc;
    use std::thread;

    use crate::logging::log_level;
    use crate::logging::log_message;
    use crate::logging::log_settings::{
        get_log_settings_provider, set_log_settings_provider, LogLevelFilter, LogSettings,
    };
    use crate::utils::jsonify;

    use super::*;

    const PROC_NAME: &str = "ee-shared-lib-logger-tests";

    fn get_log_settings(process_name: &str) -> LogSettings {
        let log_level_filter = LogLevelFilter::ERROR;

        LogSettings::new(process_name, log_level_filter)
    }

    lazy_static! {
        static ref LOG_SETTINGS_TESTS: LogSettings = get_log_settings(PROC_NAME);
    }

    #[test]
    fn should_log_structured_message() {
        let message_id: Arc<Mutex<Option<String>>> = Arc::new(Mutex::new(None));

        let message_handle = message_id.clone();

        let handle = thread::spawn(move || {
            initialize_buffered_logger();

            set_log_settings_provider(&*LOG_SETTINGS_TESTS);

            let log_settings_provider = get_log_settings_provider();

            let message_template = String::from("{correlation_id} {test-code}");

            let mut properties: BTreeMap<String, String> = BTreeMap::new();

            properties.insert("test-code".to_string(), "abc".to_string());

            let log_message = log_message::LogMessage::new_props(
                log_settings_provider,
                log_level::LogLevel::Fatal,
                message_template,
                properties,
            );

            if let Ok(mut guard) = message_handle.lock() {
                *guard = Some(log_message.message_id.value());
            }

            let json = jsonify(&log_message, false);

            log::log!(
                log_message.log_level.into(),
                "{timestamp} {loglevel} {priority} {hostname} {facility} payload={payload}",
                timestamp = log_message.timestamp,
                loglevel = log_message.log_level.to_uppercase(),
                priority = log_message.priority.value(),
                hostname = log_message.host_name.value(),
                facility = log_message.process_name.value(),
                payload = json
            );
        });

        let _r = handle.join();

        if let Ok(guard) = message_id.clone().lock() {
            assert!(guard.is_some(), "should not be None");

            let key = guard.clone().unwrap();

            let item = BUFFERED_LOGGER
                .extract(&key)
                .expect("expected item by message id)");

            assert_eq!(item.log_level, "Fatal", "expected Fatal")
        }
    }
}
