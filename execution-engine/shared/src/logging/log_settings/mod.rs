use std::process;

use serde::Serialize;

use crate::logging::log_level::*;
use crate::logging::log_message::LogMessage;

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
    pub fn filter(&self, lvl: LogLevel) -> bool {
        lvl < self.log_level_filter.0
    }
}

impl From<&LogMessage> for LogSettings {
    fn from(log_message: &LogMessage) -> LogSettings {
        LogSettings {
            log_level_filter: log_message.log_level_filter,
            process_id: log_message.process_id.clone(),
            process_name: log_message.process_name.clone(),
            host_name: log_message.host_name.clone(),
        }
    }
}

/// newtype for LogLevel when used to filter out messages of lesser priority
#[derive(Clone, Copy, Debug, Hash, Serialize)]
pub struct LogLevelFilter(pub LogLevel);

impl LogLevelFilter {
    pub fn new(log_level: LogLevel) -> LogLevelFilter {
        LogLevelFilter(log_level)
    }
}

impl Into<slog::Level> for LogLevelFilter {
    fn into(self) -> slog::Level {
        match self.0 {
            LogLevel::Fatal => slog::Level::Critical,
            LogLevel::Error => slog::Level::Error,
            LogLevel::Warning => slog::Level::Warning,
            LogLevel::Info => slog::Level::Info,
            LogLevel::Debug => slog::Level::Debug,
        }
    }
}

/// newtype to encapsulate process_id / PID
#[derive(Clone, Debug, Hash, Serialize)]
pub struct ProcessId(pub i32);

impl ProcessId {
    pub fn new(pid: i32) -> ProcessId {
        ProcessId(pid)
    }
}

/// newtype to encapsulate process_name
#[derive(Clone, Debug, Hash, Serialize)]
pub struct ProcessName(pub String);

impl ProcessName {
    pub fn new(process_name: String) -> ProcessName {
        ProcessName(process_name)
    }
}

/// newtype to encapsulate process_name
#[derive(Clone, Debug, Hash, Serialize)]
pub struct HostName(pub String);

impl HostName {
    pub fn new(host_name: String) -> HostName {
        HostName(host_name)
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

#[allow(dead_code)]
#[cfg(test)]
mod tests {
    use super::*;

    const PROC_NAME: &str = "ee-shared-log-settings-tests";

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
