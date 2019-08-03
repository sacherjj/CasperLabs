use std::process;
use std::sync::atomic::{AtomicUsize, Ordering};

use serde::Serialize;

use crate::logging::log_level::*;

static mut LOG_SETTINGS_PROVIDER: &'static LogSettingsProvider = &NopLogSettingsProvider;

static LOG_SETTINGS_STATE: AtomicUsize = AtomicUsize::new(0);

#[derive(Copy, Clone, Debug, Eq, Hash, PartialEq, Serialize)]
pub enum LogSettingsState {
    Uninitialized,
    Initializing,
    Initialized,
}

impl From<LogSettingsState> for usize {
    fn from(input: LogSettingsState) -> Self {
        match input {
            LogSettingsState::Uninitialized => 0,
            LogSettingsState::Initializing => 1,
            LogSettingsState::Initialized => 2,
        }
    }
}

impl From<usize> for LogSettingsState {
    fn from(input: usize) -> Self {
        match input {
            1 => LogSettingsState::Initializing,
            2 => LogSettingsState::Initialized,
            _ => LogSettingsState::Uninitialized,
        }
    }
}

pub fn set_log_settings_provider(log_settings_provider: &'static LogSettingsProvider) {
    match LOG_SETTINGS_STATE
        .compare_and_swap(
            <usize>::from(LogSettingsState::Uninitialized),
            <usize>::from(LogSettingsState::Initializing),
            Ordering::SeqCst,
        )
        .into()
    {
        LogSettingsState::Uninitialized => unsafe {
            LOG_SETTINGS_PROVIDER = log_settings_provider;
            LOG_SETTINGS_STATE.store(
                <usize>::from(LogSettingsState::Initialized),
                Ordering::SeqCst,
            );
        },
        LogSettingsState::Initializing => {
            while LOG_SETTINGS_STATE.load(Ordering::SeqCst)
                == <usize>::from(LogSettingsState::Initializing)
            {}
            if LOG_SETTINGS_STATE.load(Ordering::SeqCst)
                == <usize>::from(LogSettingsState::Uninitialized)
            {
                set_log_settings_provider(log_settings_provider);
            }
        }
        _ => (),
    }
}

pub(crate) fn get_log_settings_provider() -> &'static LogSettingsProvider {
    match LOG_SETTINGS_STATE.load(Ordering::SeqCst).into() {
        LogSettingsState::Initializing => get_log_settings_provider(),
        _ => unsafe { LOG_SETTINGS_PROVIDER },
    }
}

/// container for logsettings from the host
#[derive(Clone, Debug, Serialize)]
pub struct LogSettings {
    pub log_level_filter: LogLevelFilter,
    pub process_id: ProcessId,
    /// contains a string identifying the running process
    /// by convention should be a single token without whitespace
    pub process_name: ProcessName,
    pub host_name: HostName,
}

impl LogSettings {
    /// # Arguments
    ///
    /// * `process_name` - Name or key identifying the current process;
    ///       should have no spaces or punctuations by convention
    /// * `log_level_filter` - Only log messages with priority >= to this
    ///       log level will be logged
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

pub trait LogSettingsProvider {
    fn filter(&self, log_level: LogLevel) -> bool;
    fn get_process_id(&self) -> ProcessId;
    fn get_process_name(&self) -> ProcessName;
    fn get_host_name(&self) -> HostName;
    fn get_log_level_filter(&self) -> LogLevelFilter;
}

impl LogSettingsProvider for LogSettings {
    fn filter(&self, log_level: LogLevel) -> bool {
        self.filter(log_level)
    }

    fn get_process_id(&self) -> ProcessId {
        self.process_id
    }

    fn get_process_name(&self) -> ProcessName {
        self.process_name.clone()
    }

    fn get_host_name(&self) -> HostName {
        self.host_name.clone()
    }

    fn get_log_level_filter(&self) -> LogLevelFilter {
        self.log_level_filter
    }
}

struct NopLogSettingsProvider;

impl LogSettingsProvider for NopLogSettingsProvider {
    fn filter(&self, _log_level: LogLevel) -> bool {
        true
    }

    fn get_process_id(&self) -> ProcessId {
        ProcessId::new(-1)
    }

    fn get_process_name(&self) -> ProcessName {
        ProcessName::new("unknown-process".to_owned())
    }

    fn get_host_name(&self) -> HostName {
        HostName::new("unknown-host".to_owned())
    }

    fn get_log_level_filter(&self) -> LogLevelFilter {
        LogLevelFilter::new(LogLevel::Info)
    }
}

/// newtype for LogLevel when used to filter out messages of lesser priority
#[derive(Clone, Copy, Debug, Hash, PartialEq, Serialize)]
pub struct LogLevelFilter(LogLevel);

impl LogLevelFilter {
    pub const DEFAULT: LogLevelFilter = LogLevelFilter(LogLevel::Info);

    pub const ERROR: LogLevelFilter = LogLevelFilter(LogLevel::Error);

    pub fn new(log_level: LogLevel) -> LogLevelFilter {
        LogLevelFilter(log_level)
    }

    #[allow(dead_code)]
    pub(crate) fn as_log_level(self) -> LogLevel {
        self.0
    }

    /// Gets LogLevelFilter
    pub fn from_input(input: Option<&str>) -> LogLevelFilter {
        let log_level = match input {
            Some(input) => match input {
                "fatal" => LogLevel::Fatal,
                "error" => LogLevel::Error,
                "warning" => LogLevel::Warning,
                "metric" => LogLevel::Metric,
                "debug" => LogLevel::Debug,
                _ => LogLevel::Info,
            },
            None => LogLevel::Info,
        };

        LogLevelFilter::new(log_level)
    }
}

impl Into<log::Level> for LogLevelFilter {
    fn into(self) -> log::Level {
        match self.0 {
            // log::Level lacks Fatal or Critical; log::Level::Error is its max
            LogLevel::Fatal => log::Level::Error,
            LogLevel::Error => log::Level::Error,
            LogLevel::Warning => log::Level::Warn,
            LogLevel::Info => log::Level::Info,
            // metric is below info above debug for filtering purposes
            LogLevel::Metric => log::Level::Trace,
            LogLevel::Debug => log::Level::Debug,
        }
    }
}

/// newtype to encapsulate process_id / PID
#[derive(Clone, Copy, Debug, Hash, PartialEq, Serialize)]
pub struct ProcessId(i32);

impl ProcessId {
    pub fn new(pid: i32) -> ProcessId {
        ProcessId(pid)
    }

    #[allow(dead_code)]
    pub(crate) fn value(self) -> i32 {
        self.0
    }
}

/// newtype to encapsulate process_name
#[derive(Clone, Debug, Hash, PartialEq, Serialize)]
pub struct ProcessName(String);

impl ProcessName {
    pub fn new(process_name: String) -> ProcessName {
        ProcessName(process_name)
    }

    #[allow(dead_code)]
    pub(crate) fn value(&self) -> String {
        self.0.to_owned()
    }
}

/// newtype to encapsulate process_name
#[derive(Clone, Debug, Hash, PartialEq, Serialize)]
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
