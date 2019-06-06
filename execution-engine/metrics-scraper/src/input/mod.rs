use std::io;

use metrics_scraper::accumulator::Pusher;

pub(crate) fn process_line<P: Pusher<String>>(pusher: &P, line: Option<Result<String, io::Error>>) {
    match line {
        Some(Ok(l)) => {
            pusher.push(l).unwrap();
        }
        Some(Err(err)) => {
            panic!("{}", err);
        }
        None => (),
    }
}

#[cfg(test)]
mod tests {
    use std::io;
    use std::time::Duration;

    use crate::input::process_line;
    use metrics_scraper::accumulator::Accumulator;

    #[test]
    fn should_process_line() {
        let expected_poll_length = Duration::new(5, 0);
        let accumulator: Accumulator<String> = Accumulator::new(expected_poll_length);
        let pusher = accumulator.clone();
        let line: Option<Result<String, io::Error>> = Some(Ok("abc".to_string()));

        let _ = process_line(&pusher, line);

        assert!(
            !accumulator.is_empty().expect("should is_empty"),
            "accumulator should not be empty"
        );
    }
}
