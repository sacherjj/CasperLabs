use log::{Metadata, Record};
use std::sync::Once;

pub(crate) static LOGGER_INIT: Once = Once::new();

pub(crate) static TERMINAL_LOGGER: TerminalLogger = TerminalLogger;

pub(crate) struct TerminalLogger;

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

#[cfg(test)]
mod tests {
    use super::*;
    use crate::logging::log_level;
    use crate::logging::log_message;
    use crate::logging::log_settings::{LogLevelFilter, LogSettings};
    use crate::logging::utils::jsonify;

    const PROC_NAME: &str = "ee-shared-lib-logger-tests";

    #[test]
    fn should_log_structured_message() {
        let log_settings =
            LogSettings::new(PROC_NAME, LogLevelFilter::new(log_level::LogLevel::Error));

        let log_message = log_message::LogMessage::new_msg(
            log_settings,
            log_level::LogLevel::Info,
            "abc".to_string(),
        );

        let json = jsonify(&log_message, false);

        LOGGER_INIT.call_once(|| {
            log::set_logger(&TERMINAL_LOGGER).expect("TERMINAL_LOGGER should be set");
            log::set_max_level(log::LevelFilter::Trace);
        });

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
    }
}
