mod accumulator;
mod drain;
mod sink;

use std::io;
use std::time::Duration;

use accumulator::Accumulator;

fn main() -> io::Result<()> {
    // TODO: args
    let expected_poll_length = Duration::new(5, 0);
    let addr = ([127, 0, 0, 1], 3000).into();

    let accumulator: Accumulator<String> = Accumulator::new(expected_poll_length);

    drain::open_drain(Accumulator::clone(&accumulator), &addr, "/metrics");

    sink::start_sink(Accumulator::clone(&accumulator));

    Ok(())
}
