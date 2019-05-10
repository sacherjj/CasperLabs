use chrono::{DateTime, Utc};
use serde::Serialize;

/// container for log message data
#[derive(Debug, Serialize, Clone)]
pub struct LogMessage {
    // additional fields (such as error info, hashes) are forthcoming <---
    pub timestamp: TimestampRfc3999,
    pub msg: String,
}

impl LogMessage {
    pub fn new(msg: String) -> Self {
        LogMessage {
            timestamp: TimestampRfc3999::default(),
            msg,
        }
    }
}

impl From<&str> for LogMessage {
    fn from(s: &str) -> LogMessage {
        LogMessage::new(s.to_owned())
    }
}

/// newtype for Rfc3999 formatted timestamp
#[derive(Clone, Debug, Serialize)]
pub struct TimestampRfc3999(String);

impl Default for TimestampRfc3999 {
    fn default() -> Self {
        let now: DateTime<Utc> = Utc::now();
        TimestampRfc3999(now.to_rfc3339())
    }
}

#[allow(dead_code)]
#[cfg(test)]
mod tests {
    use super::*;

    //#[test]
    fn should_validate_log_message() {
        let l = super::LogMessage::new("test msg".to_owned());
        assert!(
            should_have_rfc3339_timestamp(&l),
            "rfc3339 timestamp required"
        );
        assert!(
            should_have_message_dedup_hash(&l),
            "dedup mechanism required"
        );
        assert!(should_have_short_message(&l), "short message required");
        assert!(should_have_message(&l), "message required");
        assert!(should_have_process_id(&l), "process id required");
        assert!(should_have_process_name(&l), "process name required");
        assert!(should_have_host_name(&l), "host name required");
    }

    fn should_have_rfc3339_timestamp(l: &super::LogMessage) -> bool {
        // ISO 8601 / RFC 3339
        // rfc3339 = "YYYY-MM-DDTHH:mm:ss+00:00"
        match DateTime::parse_from_rfc3339(&l.timestamp.0) {
            Ok(_d) => true,
            Err(_) => false,
        }
    }

    fn should_have_message_dedup_hash(_: &super::LogMessage) -> bool {
        unimplemented!("TODO: implement")
    }

    fn should_have_short_message(_: &super::LogMessage) -> bool {
        unimplemented!("TODO: implement")
    }

    fn should_have_message(_: &super::LogMessage) -> bool {
        unimplemented!("TODO: implement")
    }

    fn should_have_process_id(_: &super::LogMessage) -> bool {
        unimplemented!("TODO: implement")
    }

    fn should_have_process_name(_: &super::LogMessage) -> bool {
        unimplemented!("TODO: implement")
    }

    fn should_have_host_name(_: &super::LogMessage) -> bool {
        unimplemented!("TODO: implement")
    }
}
