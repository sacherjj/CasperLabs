mod accumulator;
mod drain;
mod sink;

use std::io;
use std::net::SocketAddr;
use std::time::Duration;

use clap::{App, Arg};

use accumulator::Accumulator;

const ADDR_ARG: &str = "addr";
const ADDR_ARG_SHORT: &str = "a";
const ADDR_PARSE_EXPECT: &str = "could not parse addr";
const POLL_LENGTH_ARG: &str = "poll-length";
const POLL_LENGTH_ARG_SHORT: &str = "l";
const POLL_LENGTH_PARSE_EXPECT: &str = "could not parse poll-length";

#[derive(Debug)]
struct Config {
    addr: SocketAddr,
    endpoint: &'static str,
    poll_length: Duration,
}

impl Default for Config {
    fn default() -> Self {
        Config {
            addr: ([127, 0, 0, 1], 3000).into(),
            endpoint: "/metrics",
            poll_length: Duration::new(5, 0),
        }
    }
}

fn get_config() -> Config {
    let mut ret: Config = Default::default();

    let matches = App::new("metrics-scraper")
        .arg(
            Arg::with_name(ADDR_ARG)
                .long(ADDR_ARG)
                .short(ADDR_ARG_SHORT)
                .takes_value(true),
        )
        .arg(
            Arg::with_name(POLL_LENGTH_ARG)
                .long(POLL_LENGTH_ARG)
                .short(POLL_LENGTH_ARG_SHORT)
                .takes_value(true),
        )
        .get_matches();

    if let Some(addr) = matches.value_of(ADDR_ARG) {
        ret.addr = addr.parse().expect(ADDR_PARSE_EXPECT);
    }

    if let Some(poll_length) = matches.value_of(POLL_LENGTH_ARG) {
        let millis: u64 = poll_length.parse().expect(POLL_LENGTH_PARSE_EXPECT);
        ret.poll_length = Duration::from_millis(millis)
    }

    ret
}

fn main() -> io::Result<()> {
    let config = get_config();

    let accumulator: Accumulator<String> = Accumulator::new(config.poll_length);

    drain::open_drain(
        Accumulator::clone(&accumulator),
        &config.addr,
        config.endpoint,
    );

    sink::start_sink(Accumulator::clone(&accumulator));

    Ok(())
}
