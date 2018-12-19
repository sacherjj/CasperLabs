extern crate clap;
extern crate storage;
extern crate execution_engine;

use clap::{App, Arg};
use std::io::prelude::*;
use std::fs::File;
use execution_engine::engine::EngineState;
use std::iter::Iterator;


#[derive(Debug)]
struct Task {
    path: String,
    bytes: Vec<u8>
}

fn main() {
    let default_address = "12345678901234567890";
    let matches = App::new("Execution engine standalone")
        .arg(Arg::with_name("address")
             .short("a")
             .long("address")
             .default_value(default_address)
             .value_name("BYTES")
             .required(false)
             .takes_value(true))
        .arg(Arg::with_name("wasm")
             .long("wasm")
             .multiple(true)
             .required(true)
             .index(1)
             )
        .get_matches();
    
    let wasm_files: Vec<Task> = {
        let file_str_iter = matches.values_of("wasm").expect("Wasm file not defined.");
        file_str_iter
            .map(|file_str| {
                let mut file = File::open(file_str).expect("Cannot open Wasm file");
                let mut content: Vec<u8> = Vec::new();
                let _ = file.read_to_end(&mut content).expect("Error when reading a file:");
                Task{path: String::from(file_str), bytes: content}
            })
            .collect()
    };

    let address: [u8; 20] = {
        let mut addr =  [0u8; 20];
        matches.value_of("address")
            .map(|addr| addr.as_bytes())
            .map(|bytes| addr.copy_from_slice(bytes))
            .expect("Error when parsing address");
        addr
    };

    let engine_state = EngineState::new(storage::InMemGS::new());

    for ref wasm_bytes in wasm_files.into_iter() {
        let result = engine_state.run_deploy(&wasm_bytes.bytes, address);
        println!("Result for file {}: {:?}", wasm_bytes.path, result);
    }

}
