use std::collections::hash_map::DefaultHasher;
use std::collections::BTreeMap;
use std::fmt;
use std::hash::{Hash, Hasher};

use chrono::{DateTime, SecondsFormat, Utc};
use serde::Serialize;

use crate::logging::log_level::{LogLevel, LogPriority};
use crate::logging::log_settings::{HostName, LogSettings, ProcessId, ProcessName};
use crate::semver::SemVer;

const MESSAGE_TYPE: &str = "ee-structured";

/// container for log message data
#[derive(Clone, Debug, Serialize)]
pub struct LogMessage {
    pub timestamp: TimestampRfc3999,
    pub process_id: ProcessId,
    pub process_name: ProcessName,
    pub host_name: HostName,
    pub log_level: LogLevel,
    pub priority: LogPriority,
    pub message_type: MessageType,
    pub message_type_version: SemVer,
    pub message_id: MessageId,
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
        let message_type = MessageType::new(MESSAGE_TYPE.to_string());
        let message_type_version = SemVer::V1_0_0;
        let process_id = log_settings.process_id;
        let process_name = log_settings.process_name;
        let host_name = log_settings.host_name;
        let timestamp = TimestampRfc3999::default();
        let priority = LogPriority::new(log_level);
        let properties = MessageProperties::new(properties);
        let description = properties.get_formatted_message(&message_template);

        let hash = {
            let mut state = DefaultHasher::new();
            message_type_version.hash(&mut state);
            process_id.hash(&mut state);
            process_name.hash(&mut state);
            host_name.hash(&mut state);
            timestamp.hash(&mut state);
            log_level.hash(&mut state);
            priority.hash(&mut state);
            description.hash(&mut state);
            properties.hash(&mut state);
            state.finish()
        };

        let message_id = MessageId::new(hash.to_string());

        LogMessage {
            timestamp,
            process_id,
            process_name,
            host_name,
            log_level,
            priority,
            message_type,
            message_type_version,
            message_id,
            description,
            properties,
        }
    }

    pub fn new_msg(log_settings: LogSettings, log_level: LogLevel, message: String) -> LogMessage {
        let mut properties = BTreeMap::new();

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
            level = self.log_level,
            description = self.description
        )
    }
}

/// newtype for Rfc3999 formatted timestamp
#[derive(Clone, Debug, Hash, Serialize)]
pub struct TimestampRfc3999(String);

impl Default for TimestampRfc3999 {
    fn default() -> Self {
        let now: DateTime<Utc> = Utc::now();
        TimestampRfc3999(now.to_rfc3339_opts(SecondsFormat::Millis, true))
    }
}

impl fmt::Display for TimestampRfc3999 {
    fn fmt(&self, f: &mut fmt::Formatter) -> fmt::Result {
        write!(f, "{timestamp}", timestamp = self.0.to_string())
    }
}

#[derive(Clone, Debug, Serialize)]
pub struct MessageId(String);

impl MessageId {
    pub fn new(hash: String) -> MessageId {
        MessageId(hash)
    }

    #[allow(dead_code)]
    pub(crate) fn value(&self) -> String {
        self.0.to_owned()
    }
}

/// newtype to encapsulate process_name
#[derive(Clone, Debug, Hash, Serialize)]
pub struct MessageType(String);

impl MessageType {
    pub fn new(message_type: String) -> MessageType {
        MessageType(message_type)
    }

    #[allow(dead_code)]
    pub(crate) fn value(&self) -> String {
        self.0.to_owned()
    }
}

#[derive(Clone, Debug, Hash, Serialize)]
pub struct MessageTemplate(String);

impl MessageTemplate {
    pub fn new(fmt_template: String) -> Self {
        MessageTemplate(fmt_template)
    }

    #[allow(dead_code)]
    pub(crate) fn value(&self) -> String {
        self.0.to_owned()
    }
}

#[derive(Clone, Debug, Hash, Serialize)]
pub struct MessageProperties(BTreeMap<String, String>);

impl MessageProperties {
    pub fn new(properties: BTreeMap<String, String>) -> MessageProperties {
        MessageProperties(properties)
    }

    #[allow(dead_code)]
    pub(crate) fn value(&self) -> BTreeMap<String, String> {
        self.0.to_owned()
    }

    /// strips out brace encased tokens in message_template
    /// and applies them as candidate keys for the encapsulated collection of
    /// message properties. the underlying value of any candidate key that
    /// has an entry in the collection will be spliced into the output in
    /// the place of its corresponding brace encased candidate key
    pub fn get_formatted_message(&self, message_template: &str) -> String {
        if message_template.is_empty() {
            return String::new();
        }

        let mut buf = String::new();
        let mut candidate_key = String::new();

        let mut key_seek = false;
        let properties = &self.0;

        for c in message_template.chars() {
            match c {
                '{' => {
                    key_seek = true;
                    candidate_key.clear();
                }
                '}' if key_seek => {
                    key_seek = false;
                    if let Some(v) = properties.get(&candidate_key) {
                        buf.push_str(v);
                    }
                }
                '}' => (),
                c if key_seek => candidate_key.push(c),
                c => buf.push(c),
            }
        }
        buf
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
    fn should_format_message_template_with_no_properties() {
        let properties: BTreeMap<String, String> = BTreeMap::new();

        let props = MessageProperties::new(properties);

        let template = "{message}".to_string();

        let formatted = props.get_formatted_message(&template);

        assert_eq!(formatted, "".to_string(), "message malformed")
    }

    #[test]
    fn should_format_message_template_with_unclosed_brace() {
        let properties: BTreeMap<String, String> = BTreeMap::new();

        let props = MessageProperties::new(properties);

        let template = "{message".to_string();

        let formatted = props.get_formatted_message(&template);

        assert_eq!(formatted, "".to_string(), "message malformed")
    }

    #[test]
    fn should_format_message_template_with_unopened_brace() {
        let properties: BTreeMap<String, String> = BTreeMap::new();

        let props = MessageProperties::new(properties);

        let template = "message}".to_string();

        let formatted = props.get_formatted_message(&template);

        assert_eq!(formatted, "message".to_string(), "message malformed")
    }

    #[test]
    fn should_format_message_template_with_mismatched_braces_left() {
        let properties: BTreeMap<String, String> = BTreeMap::new();

        let props = MessageProperties::new(properties);

        let template = "{{message}".to_string();

        let formatted = props.get_formatted_message(&template);

        assert_eq!(formatted, "".to_string(), "message malformed")
    }

    #[test]
    fn should_format_message_template_with_mismatched_braces_right() {
        let properties: BTreeMap<String, String> = BTreeMap::new();

        let props = MessageProperties::new(properties);

        let template = "{message}}".to_string();

        let formatted = props.get_formatted_message(&template);

        assert_eq!(formatted, "".to_string(), "message malformed")
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
        l.log_level <= LogLevel::Fatal && l.log_level >= LogLevel::Debug
    }

    fn should_have_description(l: &super::LogMessage) -> bool {
        !l.description.is_empty()
    }

    fn should_have_process_id(l: &super::LogMessage) -> bool {
        l.process_id.value() > 0
    }

    fn should_have_process_name(l: &super::LogMessage) -> bool {
        !l.process_name.value().is_empty()
    }

    fn should_have_host_name(l: &super::LogMessage) -> bool {
        !l.host_name.value().is_empty()
    }

    fn should_have_at_least_one_property(l: &super::LogMessage) -> bool {
        l.properties.value().keys().len() > 0
    }
}
