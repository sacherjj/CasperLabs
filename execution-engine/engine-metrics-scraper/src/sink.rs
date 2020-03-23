use std::io::{self, BufRead};

use serde_json::Value;

use crate::accumulator::Pusher;

const TIME_SERIES_DATA_KEY: &str = "time-series-data";
const PROPERTIES_KEY: &str = "properties";
const PAYLOAD_KEY: &str = "payload=";

fn extract_time_series_data(line: String) -> Option<String> {
    if let Some(idx) = line.find(PAYLOAD_KEY) {
        let start = idx + PAYLOAD_KEY.len();
        let end = line.len();
        let slice = &line[start..end];
        serde_json::from_str::<Value>(slice)
            .ok()
            .and_then(|full_value| full_value.get(PROPERTIES_KEY).cloned())
            .and_then(|properties_value| properties_value.get(TIME_SERIES_DATA_KEY).cloned())
            .and_then(|time_series_data_value| time_series_data_value.as_str().map(String::from))
    } else {
        None
    }
}

/// Runs a loop which parses metrics from stdin and pushes the parsed lines into
/// a given accumulator
pub fn start_sink<P: Pusher<String>>(pusher: P) {
    let stdin = io::stdin();
    let handle = stdin.lock();

    for line in handle.lines() {
        // Okay to panic here
        let line = line.unwrap();
        if let Some(parsed_line) = extract_time_series_data(line) {
            pusher.push(parsed_line).unwrap();
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn extract_time_series_data_should_parse_valid_input() {
        let expected = Some(r#"trie_store_write_duration{tag="write", correlation_id="38b81cd8-b089-42c0-bdeb-2e3dc2a91255"} 0.001382911 1559773475878"#.to_string());

        let actual = {
            let input = r#"2019-06-05T22:24:35.878Z METRIC 6 system76-pc casperlabs-engine-grpc-server payload={"timestamp":"2019-06-05T22:24:35.878Z","process_id":6507,"process_name":"casperlabs-engine-grpc-server","host_name":"system76-pc","log_level":"Metric","priority":6,"message_type":"ee-structured","message_type_version":"1.0.0","message_id":6,"description":"trie_store_write_duration write 0.001382911","properties":{"correlation_id":"38b81cd8-b089-42c0-bdeb-2e3dc2a91255","duration_in_seconds":"0.001382911","message":"trie_store_write_duration write 0.001382911","message_template":"{message}","time-series-data":"trie_store_write_duration{tag=\"write\", correlation_id=\"38b81cd8-b089-42c0-bdeb-2e3dc2a91255\"} 0.001382911 1559773475878"}}"#.to_string();
            extract_time_series_data(input)
        };

        assert_eq!(expected, actual);
    }

    #[test]
    fn extract_time_series_data_should_not_parse_invalid_input() {
        let expected = None;

        let actual = {
            let input = r#"this is invalid input"#.to_string();
            extract_time_series_data(input)
        };

        assert_eq!(expected, actual);
    }
}
