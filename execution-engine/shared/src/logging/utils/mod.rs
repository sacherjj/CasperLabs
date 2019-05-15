use serde::Serialize;

/// serializes value to json;
/// pretty_print: false = inline
/// pretty_print: true  = pretty printed / multiline
pub fn jsonify<T>(value: T, pretty_print: bool) -> String
where
    T: Serialize,
{
    let fj = if pretty_print {
        serde_json::to_string_pretty
    } else {
        serde_json::to_string
    };

    match fj(&value) {
        Ok(json) => json,
        Err(_) => "{\"error\": \"encountered error serializing value\"}".to_owned(),
    }
}

/// returns a snake_cased version of input
pub fn snakeify(input: String) -> String {
    if input.is_empty() {
        return input;
    }

    const SEPARATORS: [char; 5] = ['-', '-', '_', ' ', '.'];

    input
        .trim_start_matches(|c| SEPARATORS.contains(&c))
        .trim_end_matches(|c| SEPARATORS.contains(&c))
        .chars()
        .enumerate()
        .fold("".to_string(), |mut snake, ci: (_, char)| {
            let c = ci.1;
            if SEPARATORS.contains(&c) {
                snake.push('_');
            } else if !c.is_uppercase() {
                snake.push(c);
            } else {
                snake.push(c.to_ascii_lowercase());
            }

            snake
        })
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde::{Deserialize, Serialize};

    #[derive(Clone, Debug, Deserialize, Eq, Serialize)]
    struct SerMock {
        foo: String,
        bar: u32,
    }

    impl PartialEq for SerMock {
        fn eq(&self, other: &SerMock) -> bool {
            self.foo.eq(&other.foo) && self.bar == other.bar
        }
    }

    #[test]
    fn should_ser_to_json() {
        let sermock = SerMock {
            foo: "foo".to_string(),
            bar: 1,
        };

        let json = jsonify(sermock, false);

        assert_eq!(
            json, "{\"foo\":\"foo\",\"bar\":1}",
            "json expected to match"
        );
    }

    #[test]
    fn should_ser_to_pretty_json() {
        let sermock = SerMock {
            foo: "foo".to_string(),
            bar: 1,
        };

        let json = jsonify(sermock, true);

        let expected_value = "{\n  \"foo\": \"foo\",\n  \"bar\": 1\n}";

        assert_eq!(json, expected_value, "json expected to match");
    }

    #[test]
    fn should_deser_from_json() {
        let sermock = SerMock {
            foo: "foo".to_string(),
            bar: 1,
        };

        let json = jsonify(&sermock, false);

        let sermock_clone: SerMock = serde_json::from_str(&json).expect("should deser");

        assert!(
            sermock.eq(&sermock_clone),
            "instances should contain the same data"
        );
    }

    #[test]
    fn should_snake_case() {
        let snakey = snakeify("I am.A-Str6n9e_stRing".to_owned());

        assert_eq!(snakey, "i_am_a_str6n9e_string", "expected snake case")
    }

    #[test]
    fn should_trim_snake_case() {
        let snakey = snakeify(" .-_ I am.A-Str6n9e_stRing .-_ ".to_owned());

        assert_eq!(snakey, "i_am_a_str6n9e_string", "expected snake case")
    }
}
