use std::process;

use serde::Serialize;

use crate::logging::log_level::*;
use crate::logging::utils::snakeify;

/// container for logsettings from the host
#[derive(Clone, Debug, Serialize)]
pub struct LogSettings {
    pub log_level_filter: LogLevelFilter,
    pub process_id: ProcessId,
    pub process_name: ProcessName,
    pub host_name: HostName,
}

impl LogSettings {
    pub fn new(process_name: &str, log_level_filter: LogLevelFilter) -> LogSettings {
        LogSettings {
            log_level_filter,
            process_id: ProcessId::new(*PID),
            process_name: ProcessName::new(process_name.to_owned()),
            host_name: HostName::new(HOSTNAME.clone()),
        }
    }

    /// if lvl is less than settings loglevel, associated msg should be filtered out
    pub fn filter(&self, log_level: LogLevel) -> bool {
        log_level < self.log_level_filter.0
    }
}

/// newtype for LogLevel when used to filter out messages of lesser priority
#[derive(Clone, Copy, Debug, Hash, Serialize)]
pub struct LogLevelFilter(LogLevel);

impl LogLevelFilter {
    pub fn new(log_level: LogLevel) -> LogLevelFilter {
        LogLevelFilter(log_level)
    }

    #[allow(dead_code)]
    pub(crate) fn as_log_level(self) -> LogLevel {
        self.0
    }
}

impl Into<log::Level> for LogLevelFilter {
    fn into(self) -> log::Level {
        match self.0 {
            LogLevel::Fatal => log::Level::Error,
            LogLevel::Error => log::Level::Error,
            LogLevel::Warning => log::Level::Warn,
            LogLevel::Info => log::Level::Info,
            LogLevel::Debug => log::Level::Debug,
        }
    }
}

/// newtype to encapsulate process_id / PID
#[derive(Clone, Debug, Hash, Serialize)]
pub struct ProcessId(i32);

impl ProcessId {
    pub fn new(pid: i32) -> ProcessId {
        ProcessId(pid)
    }

    #[allow(dead_code)]
    pub(crate) fn value(&self) -> i32 {
        self.0
    }
}

/// newtype to encapsulate process_name
#[derive(Clone, Debug, Hash, Serialize)]
pub struct ProcessName(String);

impl ProcessName {
    pub fn new(process_name: String) -> ProcessName {
        ProcessName(process_name)
    }

    #[allow(dead_code)]
    pub(crate) fn value(&self) -> String {
        self.0.to_owned()
    }

    pub(crate) fn snake_case(&self) -> String {
        snakeify(self.0.to_owned())
    }
}

/// newtype to encapsulate process_name
#[derive(Clone, Debug, Hash, Serialize)]
pub struct HostName(String);

impl HostName {
    pub fn new(host_name: String) -> HostName {
        HostName(host_name)
    }

    #[allow(dead_code)]
    pub(crate) fn value(&self) -> String {
        self.0.to_owned()
    }
}

lazy_static! {
    pub(crate) static ref PID: i32 = get_pid();
}

fn get_pid() -> i32 {
    process::id() as i32
}

lazy_static! {
    pub(crate) static ref HOSTNAME: String = get_hostname();
}

fn get_hostname() -> String {
    match hostname::get_hostname() {
        Some(h) => h.to_string(),
        None => "unknown-host".to_owned(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn should_get_host_name() {
        let host_name = &HOSTNAME;
        assert!(host_name.len() > 0, "host_name should have chars")
    }

    #[test]
    fn should_get_process_id() {
        let pid = *super::PID;
        assert!(pid != 0, print!("pid should not be 0: {}", pid));
        let pid_again = *super::PID;
        assert_eq!(
            pid, pid_again,
            "pid should not change per process lifecycle"
        );
    }
}
