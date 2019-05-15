///! Log levels are [ fatal | error | warning | info | debug ]
///!    all emergency, alert, and critical level log events will be coalesced into
///         a single category, fatal
///!    all notice and informational level log events will be coalesced into
///         a single category, info
///!    "no log" / "none" is NOT an option; the minimum allowed loglevel is fatal
///!   internally, syslog levels will be maintained for cross compatibility, with
///!        the following mapping:
///!            fatal: 0
///!            error: 3
///!            warning: 4
///!            info: 5
///!            debug: 7
use std::cmp::{Ord, Ordering};
use std::fmt;

use serde::{Deserialize, Serialize};

/// LogLevels to be used in CasperLabs EE logic
#[repr(u8)] // https://doc.rust-lang.org/1.6.0/nomicon/other-reprs.html
#[derive(Copy, Clone, Debug, Deserialize, Eq, Hash, PartialEq, Serialize)]
pub enum LogLevel {
    /// emergency, alert, critical
    Fatal = 0,
    /// error
    Error = 3,
    /// warnings
    Warning = 4,
    /// notice, informational
    Info = 5,
    /// debug, dev oriented messages
    Debug = 7,
}

impl LogLevel {
    pub fn get_priority(self) -> LogPriority {
        LogPriority::new(self)
    }

    pub fn value(self) -> u8 {
        self as u8
    }

    pub fn to_uppercase(self) -> String {
        (format!("{:?}", self)).to_uppercase()
    }
}

impl Ord for LogLevel {
    /// invert numeric ordering to match semantic ordering
    fn cmp(&self, other: &Self) -> Ordering {
        // semantic ordering is reversed; Fatal is most severe, Debug is least
        let x: u8 = *self as u8;
        let y: u8 = *other as u8;
        if x == y {
            return Ordering::Equal;
        }

        if x > y {
            return Ordering::Less;
        }

        Ordering::Greater
    }

    fn max(self, other: Self) -> Self
    where
        Self: Sized,
    {
        // semantic ordering is reversed; Fatal is most severe, Debug is least
        let x: u8 = self as u8;
        let y: u8 = other as u8;

        // Returns the second argument if the comparison determines them to be equal.
        if x == y {
            return other;
        }

        if x > y {
            return other;
        }

        self
    }

    fn min(self, other: Self) -> Self
    where
        Self: Sized,
    {
        // semantic ordering is reversed; Fatal is most severe, Debug is least
        let x: u8 = self as u8;
        let y: u8 = other as u8;

        // Returns the first argument if the comparison determines them to be equal.
        if x == y {
            return self;
        }

        if x > y {
            return self;
        }

        other
    }
}

impl PartialOrd for LogLevel {
    fn partial_cmp(&self, other: &Self) -> Option<Ordering> {
        Some(self.cmp(&other))
    }
}

impl fmt::Display for LogLevel {
    fn fmt(&self, f: &mut fmt::Formatter) -> fmt::Result {
        write!(f, "{}", self.to_string())
    }
}

impl Into<log::Level> for LogLevel {
    fn into(self) -> log::Level {
        match self {
            LogLevel::Fatal => log::Level::Error,
            LogLevel::Error => log::Level::Error,
            LogLevel::Warning => log::Level::Warn,
            LogLevel::Info => log::Level::Info,
            LogLevel::Debug => log::Level::Debug,
        }
    }
}

/// newtype to encapsulate log level priority
#[derive(Clone, Debug, Hash, Serialize)]
pub struct LogPriority(u8);

impl LogPriority {
    pub fn new(log_level: LogLevel) -> LogPriority {
        let priority = log_level.value();
        LogPriority(priority)
    }

    #[allow(dead_code)]
    pub(crate) fn value(&self) -> u8 {
        self.0
    }
}

impl fmt::Display for LogPriority {
    fn fmt(&self, f: &mut fmt::Formatter) -> fmt::Result {
        write!(f, "{}", self.0.to_string())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn fatal_should_be_greater() {
        let lvl = LogLevel::Fatal;
        let other = LogLevel::Error;

        assert_eq!(
            lvl.cmp(&other),
            Ordering::Greater,
            "fatal should be most severe"
        );
    }

    #[test]
    fn debug_should_be_less() {
        let lvl = LogLevel::Debug;
        let other = LogLevel::Info;

        assert_eq!(
            lvl.cmp(&other),
            Ordering::Less,
            "debug should be least severe"
        );
    }

    #[test]
    fn fatal_should_equal_fatal() {
        let x = LogLevel::Fatal;
        let y = LogLevel::Fatal;
        assert!(x.eq(&y), "fatal should = fatal");
        assert_eq!(
            x.cmp(&y),
            Ordering::Equal,
            "fatal should have same order as fatal"
        );
    }

    #[test]
    fn fatal_should_be_gt_error() {
        assert!(LogLevel::Fatal > LogLevel::Error, "fatal should be > error");
    }

    #[test]
    fn error_should_be_lt_fatal() {
        assert!(LogLevel::Error < LogLevel::Fatal, "error should be < fatal");
    }

    #[test]
    fn fatal_should_be_max() {
        let lvl = LogLevel::Fatal;
        let other = LogLevel::Error;

        let max = lvl.max(other);

        assert_eq!(max, LogLevel::Fatal, "fatal is most severe");
    }

    #[test]
    fn debug_should_be_min() {
        let lvl = LogLevel::Warning;
        let other = LogLevel::Debug;

        let min = lvl.min(other);

        assert_eq!(min, LogLevel::Debug, "debug should be least severe");
    }

    #[test]
    fn error_should_be_min_vs_fatal() {
        let lvl = LogLevel::Error;
        let other = LogLevel::Fatal;

        let min = lvl.min(other);

        assert_eq!(min, LogLevel::Error, "error should be less severe");
    }

    #[test]
    fn fatal_should_be_syslog_0() {
        assert_eq!(LogLevel::Fatal as u8, 0, "Fatal should be priority 0");
    }

    #[test]
    fn error_should_be_syslog_3() {
        assert_eq!(LogLevel::Error as u8, 3, "Error should be priority 3");
    }

    #[test]
    fn warning_should_be_syslog_4() {
        assert_eq!(LogLevel::Warning as u8, 4, "Warning should be priority 4");
    }

    #[test]
    fn info_should_be_syslog_5() {
        assert_eq!(LogLevel::Info as u8, 5, "Info should be priority 5");
    }

    #[test]
    fn debug_should_be_syslog_7() {
        assert_eq!(LogLevel::Debug as u8, 7, "Debug should be priority 7");
    }

    #[test]
    fn should_get_loglevel_priority() {
        assert_eq!(LogLevel::Warning.value(), 4, "warn should be 4");
    }

    #[test]
    fn should_get_loglevel_item_priority() {
        let ll = LogLevel::Error;
        assert_eq!(ll.value(), 3, "error should be 3");
    }

    #[test]
    fn should_get_log_priority_fm_log_level() {
        let log_level = LogLevel::Info;

        let log_priority = LogPriority::new(log_level);

        let priority = log_priority.value();

        assert_eq!(log_level.value(), priority, "priority mismatch");
    }

    #[test]
    fn should_get_uppercase_label() {
        let log_level = LogLevel::Info;
        assert_eq!(
            log_level.to_uppercase(),
            "INFO".to_string(),
            "expected uppercase"
        );
    }

    #[test]
    fn log_error_eq_fatal() {
        let ll: log::Level = LogLevel::Fatal.into();
        assert_eq!(ll, log::Level::Error, "Fatal eq Error");
    }

    #[test]
    fn log_error_eq_error() {
        let ll: log::Level = LogLevel::Error.into();
        assert_eq!(ll, log::Level::Error, "Error eq Error");
    }

    #[test]
    fn log_warn_eq_warning() {
        let ll: log::Level = LogLevel::Warning.into();
        assert_eq!(ll, log::Level::Warn, "Warn eq Warning");
    }

    #[test]
    fn log_info_eq_info() {
        let ll: log::Level = LogLevel::Info.into();
        assert_eq!(ll, log::Level::Info, "Info eq Info");
    }

    #[test]
    fn log_debug_eq_debug() {
        let ll: log::Level = LogLevel::Debug.into();
        assert_eq!(ll, log::Level::Debug, "Debug eq Debug");
    }
}
