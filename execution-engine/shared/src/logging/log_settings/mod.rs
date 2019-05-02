use std::net::IpAddr;

use crate::logging::log_level::*;

/// container for logsettings from the host
pub struct LogSettings {
    pub log_level: LogLevel,
    pub process_id: ProcessId,
    pub process_name: ProcessName,
    pub ip: IpAddr,
}

impl LogSettings {
    pub fn new(_lvl: LogLevel) -> LogSettings {
        // get pid, procname, hostname, ip etc then return
        unimplemented!("TODO: implement")
    }

    /// if lvl is less than settings loglevel, associated msg should be filtered out
    pub fn filter(&self, lvl: LogLevel) -> bool {
        lvl < self.log_level
    }
}

/// newtype to encapsulate process_id / PID
pub struct ProcessId(i32);

impl ProcessId {
    pub fn new(pid: i32) -> ProcessId {
        ProcessId(pid)
    }
}

/// newtype to encapsulate process_name
pub struct ProcessName(String);

impl ProcessName {
    pub fn new(process_name: String) -> ProcessName {
        ProcessName(process_name)
    }
}

#[allow(dead_code)]
#[cfg(test)]
mod tests {
    use super::*;
    use std::net::Ipv4Addr;

    #[test]
    fn should_ctor_ip() {
        let ipv4 = IpAddr::V4(Ipv4Addr::new(0, 0, 0, 0));
        assert!(ipv4.is_ipv4(), "should be ipv4");
    }

    //#[test]
    fn should_get_current_ip() {
        unimplemented!("TODO: implement")
    }

    //#[test]
    fn should_get_host_name() {
        unimplemented!("TODO: implement")
    }

    //#[test]
    fn should_get_process_id() {
        unimplemented!("TODO: implement")
    }

    //#[test]
    fn should_get_process_name() {
        unimplemented!("TODO: implement")
    }
}
