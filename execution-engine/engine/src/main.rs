extern crate clap;

use clap::{App, Arg};

fn main() {
    let matches = App::new("Execution engine standalone")
        .arg(Arg::with_name("address")
             .short("a")
             .long("address")
             .value_name("BYTES")
             .required(true)
             .takes_value(true))
        .arg(Arg::with_name("wasm file")
             .required(true)
             .index(1)
             )
        .get_matches();

    println!("Arguments: {:?}", matches.args);
}
