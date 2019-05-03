use std::collections::btree_map::BTreeMap;
use std::collections::hash_map::DefaultHasher;
use std::fmt;
use std::hash::{Hash, Hasher};

use chrono::{DateTime, Utc};
use serde::Serialize;

use crate::logging::log_level::{LogLevel, LogPriority};
use crate::logging::log_settings::{HostName, LogLevelFilter, LogSettings, ProcessId, ProcessName};
use crate::semver::SemVer;

/// container for log message data
#[derive(Clone, Debug, Serialize)]
pub struct LogMessage {
    pub message_id: MessageId,
    pub message_version: SemVer,
    pub process_id: ProcessId,
    pub process_name: ProcessName,
    pub host_name: HostName,
    pub timestamp: TimestampRfc3999,
    pub log_level_filter: LogLevelFilter,
    pub level: LogLevel,
    pub priority: LogPriority,
    pub description: String,
    pub properties: MessageProperties,
}

impl LogMessage {
    pub fn new_props(
        log_settings: LogSettings,
        log_level: LogLevel,
        message_template: String,
        properties: BTreeMap<String, String>,
    ) -> LogMessage {
        let props = MessageProperties::new(properties);

        let desc = props.get_formatted_message(&message_template);

        let inner = LogMessageData {
            message_version: SemVer::V1_0_0,
            process_id: log_settings.process_id,
            process_name: log_settings.process_name,
            host_name: log_settings.host_name,
            timestamp: TimestampRfc3999::default(),
            log_level_filter: log_settings.log_level_filter,
            level: log_level,
            priority: LogPriority::new(log_level),
            properties: props,
            description: desc,
        };

        let mut state = DefaultHasher::new();
        inner.hash(&mut state);
        let hash = state.finish();

        inner.transform(MessageId::new(hash.to_string()))
    }

    pub fn new_msg(log_settings: LogSettings, log_level: LogLevel, message: String) -> LogMessage {
        let mut properties: BTreeMap<String, String> = BTreeMap::new();

        properties.insert("message".to_owned(), message);

        Self::new_props(log_settings, log_level, "{message}".to_owned(), properties)
    }
}

impl fmt::Display for LogMessage {
    fn fmt(&self, f: &mut fmt::Formatter) -> fmt::Result {
        write!(
            f,
            "{timestamp} priority:{priority}|{level} {description}",
            timestamp = self.timestamp,
            priority = self.priority,
            level = self.level,
            description = self.description
        )
    }
}

impl From<(LogSettings, LogLevel, String, BTreeMap<String, String>)> for LogMessage {
    fn from(t: (LogSettings, LogLevel, String, BTreeMap<String, String>)) -> LogMessage {
        LogMessage::new_props(t.0, t.1, t.2, t.3)
    }
}

/// newtype for Rfc3999 formatted timestamp
#[derive(Clone, Debug, Hash, Serialize)]
pub struct TimestampRfc3999(pub String);

impl Default for TimestampRfc3999 {
    fn default() -> Self {
        let now: DateTime<Utc> = Utc::now();
        TimestampRfc3999(now.to_rfc3339())
    }
}

impl fmt::Display for TimestampRfc3999 {
    fn fmt(&self, f: &mut fmt::Formatter) -> fmt::Result {
        write!(f, "{timestamp}", timestamp = self.0.to_string())
    }
}

#[derive(Clone, Debug, Hash, Serialize)]
pub struct MessageTemplate(pub String);

impl MessageTemplate {
    pub fn new(fmt_template: String) -> Self {
        MessageTemplate(fmt_template)
    }
}

#[derive(Clone, Debug, Serialize)]
pub struct MessageId(pub String);

impl MessageId {
    pub fn new(hash: String) -> MessageId {
        MessageId(hash)
    }
}

#[derive(Clone, Debug, Hash, Serialize)]
pub struct MessageProperties(pub BTreeMap<String, String>);

impl MessageProperties {
    pub fn new(properties: BTreeMap<String, String>) -> MessageProperties {
        MessageProperties(properties)
    }

    /// strips out brace encased tokens in message_template
    /// and applies them as candidate keys for the encased collection of
    /// message properties. the underlying value of any candidate key that
    /// has an entry in the collection will be spliced into the output in
    /// the place of it's corresponding brace encased candidate key
    pub fn get_formatted_message(&self, message_template: &str) -> String {
        if message_template.is_empty() {
            return String::new();
        }

        if self.0.keys().len() == 0 {
            return String::new();
        }

        const BRL: char = '{';
        const BRR: char = '}';

        let mut buf: String = String::new();
        let mut candidate_key: String = String::new();

        let mut key_seek: bool = false;
        let mut key_lookup: bool = false;

        let mut exit: bool = false;
        let mut ci = message_template.char_indices();

        loop {
            let mut oc: Option<char> = None;

            match ci.next() {
                Some(x) => {
                    let c = x.1;

                    // multiple opening braces should be caught
                    if c.eq(&BRL) {
                        // flag key seek behavior
                        key_seek = true;

                        // end key lookup behavior
                        key_lookup = false;

                        // reset candidate key
                        if !candidate_key.is_empty() {
                            candidate_key.clear();
                        }

                        continue;
                    }

                    // multiple closing braces should be caught
                    if c.eq(&BRR) {
                        // end key siphon
                        key_seek = false;

                        // flag key look up behavior
                        key_lookup = true;

                        continue;
                    }

                    //build up candidate key
                    if key_seek {
                        candidate_key.push(c);

                        continue;
                    }

                    // otherwise, capture current char
                    oc = Some(c);
                }
                None => {
                    exit = true;
                }
            }

            // lookup candidate key
            if key_lookup {
                key_lookup = false;

                if let Some(v) = self.0.get(&candidate_key) {
                    // buffer keyed val
                    buf.push_str(v);
                }
            }

            if let Some(c) = oc {
                buf.push(c);
            }

            if exit {
                break;
            }
        }

        buf
    }
}

#[derive(Clone, Debug, Serialize)]
struct LogMessageData {
    pub message_version: SemVer,
    pub process_id: ProcessId,
    pub process_name: ProcessName,
    pub host_name: HostName,
    pub timestamp: TimestampRfc3999,
    pub log_level_filter: LogLevelFilter,
    pub level: LogLevel,
    pub priority: LogPriority,
    pub description: String,
    pub properties: MessageProperties,
}

impl LogMessageData {
    fn transform(self, message_id: MessageId) -> LogMessage {
        LogMessage {
            message_id,
            message_version: self.message_version,
            process_id: self.process_id,
            process_name: self.process_name,
            host_name: self.host_name,
            timestamp: self.timestamp,
            log_level_filter: self.log_level_filter,
            level: self.level,
            priority: self.priority,
            description: self.description,
            properties: self.properties,
        }
    }
}

impl Hash for LogMessageData {
    fn hash<H: Hasher>(&self, state: &mut H) {
        self.message_version.hash(state);
        self.process_id.hash(state);
        self.process_name.hash(state);
        self.host_name.hash(state);
        self.timestamp.hash(state);
        self.level.hash(state);
        self.priority.hash(state);
        self.description.hash(state);
        self.properties.hash(state);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::logging::log_settings::LogLevelFilter;

    #[test]
    fn should_format_message_template_defautl_use_case() {
        let mut properties: BTreeMap<String, String> = BTreeMap::new();
        properties.insert("message".to_string(), "i am a log message".to_string());

        let props = MessageProperties::new(properties);

        let template = "{message}".to_string();

        let formatted = props.get_formatted_message(&template);

        assert_eq!(
            formatted,
            "i am a log message".to_string(),
            "message malformed"
        )
    }

    #[test]
    fn should_format_message_template_starting_and_ending_with_braces() {
        let mut properties: BTreeMap<String, String> = BTreeMap::new();
        properties.insert("message".to_string(), "i convey meaning".to_string());
        properties.insert("abc".to_string(), "some text".to_string());
        properties.insert("some-hash".to_string(), "A@#$!@#".to_string());
        properties.insert("byz".to_string(), "".to_string());

        let props = MessageProperties::new(properties);

        let template =
            "{abc} i'm a message temp{byz}late some-hash:{some-hash} msg:{message}".to_string();

        let formatted = props.get_formatted_message(&template);

        assert_eq!(
            formatted,
            "some text i\'m a message template some-hash:A@#$!@# msg:i convey meaning".to_string(),
            "message malformed"
        )
    }

    #[test]
    fn should_format_message_template_with_escaped_braces() {
        let mut properties: BTreeMap<String, String> = BTreeMap::new();
        properties.insert("message".to_string(), "a message".to_string());
        properties.insert("more-data".to_string(), "some additional data".to_string());

        let props = MessageProperties::new(properties);

        let template = "this is {{message}} with {{{more-data}}}".to_string();

        let formatted = props.get_formatted_message(&template);

        assert_eq!(
            formatted,
            "this is a message with some additional data".to_string(),
            "message malformed"
        )
    }

    #[test]
    fn should_validate_log_message() {
        let settings = LogSettings::new("log_message_tests", LogLevelFilter::new(LogLevel::Error));

        let l = super::LogMessage::new_msg(settings, LogLevel::Error, "test msg".to_owned());

        assert!(
            should_have_rfc3339_timestamp(&l),
            "rfc3339 timestamp required"
        );
        assert!(
            should_have_message_dedup_hash(&l),
            "dedup mechanism required"
        );

        assert!(should_have_log_level(&l), "log level required");
        assert!(should_have_process_id(&l), "process id required");
        assert!(should_have_process_name(&l), "process name required");
        assert!(should_have_host_name(&l), "host name required");
        assert!(should_have_at_least_one_property(&l), "properties required");
        assert!(should_have_description(&l), "description required");
    }

    fn should_have_rfc3339_timestamp(l: &super::LogMessage) -> bool {
        // ISO 8601 / RFC 3339
        // rfc3339 = "YYYY-MM-DDTHH:mm:ss+00:00"
        match DateTime::parse_from_rfc3339(&l.timestamp.0) {
            Ok(_d) => true,
            Err(_) => false,
        }
    }

    fn should_have_message_dedup_hash(l: &super::LogMessage) -> bool {
        !l.message_id.0.is_empty()
    }

    fn should_have_log_level(l: &super::LogMessage) -> bool {
        l.level <= LogLevel::Fatal && l.level >= LogLevel::Debug
    }

    fn should_have_description(l: &super::LogMessage) -> bool {
        !l.description.is_empty()
    }

    fn should_have_process_id(l: &super::LogMessage) -> bool {
        l.process_id.0 > 0
    }

    fn should_have_process_name(l: &super::LogMessage) -> bool {
        !l.process_name.0.is_empty()
    }

    fn should_have_host_name(l: &super::LogMessage) -> bool {
        !l.host_name.0.is_empty()
    }

    fn should_have_at_least_one_property(l: &super::LogMessage) -> bool {
        l.properties.0.keys().len() > 0
    }
}
