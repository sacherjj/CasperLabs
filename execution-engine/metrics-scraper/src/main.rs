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
const EXPIRATION_DURATION_ARG: &str = "expiration-duration-millis";
const EXPIRATION_DURATION_ARG_SHORT: &str = "e";
const EXPIRATION_DURATION_PARSE_EXPECT: &str = "could not parse expiration-duration-millis";

#[derive(Debug)]
struct Config {
    addr: SocketAddr,
    endpoint: &'static str,
    expiration_duration: Duration,
}

impl Default for Config {
    fn default() -> Self {
        Config {
            addr: ([127, 0, 0, 1], 3000).into(),
            endpoint: "/metrics",
            expiration_duration: Duration::new(5, 0),
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
            Arg::with_name(EXPIRATION_DURATION_ARG)
                .long(EXPIRATION_DURATION_ARG)
                .short(EXPIRATION_DURATION_ARG_SHORT)
                .takes_value(true),
        )
        .get_matches();

    if let Some(addr) = matches.value_of(ADDR_ARG) {
        ret.addr = addr.parse().expect(ADDR_PARSE_EXPECT);
    }

    if let Some(expiration_duration) = matches.value_of(EXPIRATION_DURATION_ARG) {
        let millis: u64 = expiration_duration
            .parse()
            .expect(EXPIRATION_DURATION_PARSE_EXPECT);
        ret.expiration_duration = Duration::from_millis(millis)
    }

    ret
}

fn main() -> io::Result<()> {
    let config = get_config();

    let accumulator: Accumulator<String> = Accumulator::new(config.expiration_duration);

    drain::open_drain(
        Accumulator::clone(&accumulator),
        &config.addr,
        config.endpoint,
    );

    sink::start_sink(Accumulator::clone(&accumulator));

    Ok(())
}
