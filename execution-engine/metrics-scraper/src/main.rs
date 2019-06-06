use std::io::{self, BufRead};
use std::time::Duration;

use crate::input::process_line;
use crate::output::open_drain;
use metrics_scraper::accumulator::Accumulator;

pub mod input;
pub mod output;

fn main() -> io::Result<()> {
    // TODO: args
    let expected_poll_length = Duration::new(5, 0);
    let addr = ([127, 0, 0, 1], 3000).into();

    let accumulator: Accumulator<String> = Accumulator::new(expected_poll_length);

    {
        let _ = open_drain(accumulator.clone(), &addr);
    }

    {
        let pusher = accumulator.clone();
        let stdin = io::stdin();
        let handle = stdin.lock();

        let mut iter = handle.lines();
        loop {
            process_line(&pusher, iter.next());
        }
    }
}
