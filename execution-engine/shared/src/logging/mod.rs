use slog::Drain;

use crate::logging::log_level::LogLevel;
use crate::logging::log_settings::LogSettings;

pub mod log_level;
pub mod log_message;
pub mod log_settings;

/// for initial pass, I intend to try using an ambient logger approach and wrap slog behind it
/// hopefully that will prove viable and minimize disruption to existing code
/// however if it proves impractical, will refactor to instead offer builder support
/// for slogger and mod comm / engine to pass around slog loggers.
pub fn log<T>(ls: LogSettings, lvl: LogLevel, value: T)
where
    T: Into<log_message::LogMessage>,
{
    if ls.filter(lvl) {
        // slog has a filtering mechanic, but it appears to only be settable via configuration
        // so, for now, applying filter directly as a guard
        return;
    }

    let msg = get_msg(value, false);

    let logger = get_logger();

    // it is possible to bypass crit!, error! etc and log directly to log!
    // which seems more expedient and sensible to me.
    // however in the slog docs that doesn't appear to be a done thing;
    // so for now using crit!, error!, etc macros
    // log!(logger, slog::Level::Debug, "", "{}", msg);

    match lvl {
        LogLevel::Fatal => {
            crit!(logger, "{}", msg);
        }
        LogLevel::Error => {
            error!(logger, "{}", msg);
        }
        LogLevel::Warning => {
            warn!(logger, "{}", msg);
        }
        LogLevel::Info => {
            info!(logger, "{}", msg);
        }
        LogLevel::Debug => {
            debug!(logger, "{}", msg);
        }
    }
}

/// serializes value to json;
/// pretty_print: false = inline
/// pretty_print: true  = pretty printed / multiline
fn get_msg<T>(value: T, pretty_print: bool) -> String
where
    T: Into<log_message::LogMessage>,
{
    let lm = value.into();

    if pretty_print {
        match serde_json::to_string_pretty(&lm) {
            Ok(json) => json,
            Err(_) => "{\"error\": \"encountered error serializing logmessage\"}".to_owned(),
        }
    } else {
        match serde_json::to_string(&lm) {
            Ok(json) => json,
            Err(_) => "{\"error\": \"encountered error serializing logmessage\"}".to_owned(),
        }
    }
}

/// factory method to build and return a slog logger configured as a async terminal writer
fn get_logger() -> slog::Logger {
    // temporal dependencies herein; order of nesting drains and loggers matters

    let decorator = slog_term::TermDecorator::new().build();

    let drain = slog_term::FullFormat::new(decorator).build().fuse();

    // the async drain ideally is the outermost drain
    let drain = slog_async::Async::new(drain).build().fuse();

    slog::Logger::root(drain, o!())
}

#[allow(dead_code)]
#[cfg(test)]
mod tests {
    use super::*;
    use crate::logging::log_message::LogMessage;

    // TODO: figure out a good way to test logging...possibly a drain impl
    //#[test]
    fn should_log() {
        let settings = log_settings::LogSettings::new(LogLevel::Fatal);
        let lm = LogMessage::new("this is a logmessage".to_owned());
        super::log(settings, LogLevel::Fatal, lm);
    }

    // TODO: figure out a good way to test logging...possibly a drain impl
    //#[test]
    fn should_not_log() {
        let settings = log_settings::LogSettings::new(LogLevel::Fatal);
        super::log(settings, LogLevel::Error, "this wont log");
    }

    //#[test]
    fn should_log_stir() {
        let settings = log_settings::LogSettings::new(LogLevel::Fatal);
        super::log(settings, LogLevel::Fatal, "this is a stir");
    }
}
