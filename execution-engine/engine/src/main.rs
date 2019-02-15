extern crate clap;
extern crate common;
extern crate execution_engine;
extern crate storage;

use std::fs::File;
use std::io::prelude::*;
use std::iter::Iterator;

use clap::{App, Arg};

use execution_engine::engine::EngineState;
use storage::gs::inmem::InMemHist;

#[derive(Debug)]
struct Task {
    path: String,
    bytes: Vec<u8>,
}

fn main() {
    let default_address = "00000000000000000000";
    let default_gas_limit: &str = &std::u64::MAX.to_string();
    let matches = App::new("Execution engine standalone")
        .arg(
            Arg::with_name("address")
                .short("a")
                .long("address")
                .default_value(default_address)
                .value_name("BYTES")
                .required(false)
                .takes_value(true),
        )
        .arg(
            Arg::with_name("gas-limit")
                .short("l")
                .long("gas-limit")
                .default_value(default_gas_limit)
                .required(false)
                .takes_value(true),
        )
        .arg(
            Arg::with_name("wasm")
                .long("wasm")
                .multiple(true)
                .required(true)
                .index(1),
        )
        .get_matches();

    let wasm_files: Vec<Task> = {
        let file_str_iter = matches.values_of("wasm").expect("Wasm file not defined.");
        file_str_iter
            .map(|file_str| {
                let mut file = File::open(file_str).expect("Cannot open Wasm file");
                let mut content: Vec<u8> = Vec::new();
                let _ = file
                    .read_to_end(&mut content)
                    .expect("Error when reading a file:");
                Task {
                    path: String::from(file_str),
                    bytes: content,
                }
            })
            .collect()
    };

    let account_addr: [u8; 20] = {
        let mut address = [48u8; 20];
        matches
            .value_of("address")
            .map(|addr| addr.as_bytes())
            .map(|bytes| address.copy_from_slice(bytes))
            .expect("Error when parsing address");
        address
    };

    let mut state_hash = [0u8; 32];

    let gas_limit: u64 = matches
        .value_of("gas-limit")
        .and_then(|v| v.parse::<u64>().ok())
        .expect("Provided gas limit value is not u64.");

    // TODO: move to arg parser
    let timestamp: i64 = 100000;
    let nonce: i64 = 1;

    let path = std::path::Path::new("./tmp/");
    //TODO: Better error handling?
    //    let gs = LmdbGs::new(&path).unwrap();
    let gs = InMemHist::new(&state_hash);
    let engine_state = {
        let state = EngineState::new(gs);
        let post_hash = state.with_mocked_account(state_hash, account_addr);
        println!("Hash after creating mock account {:?}", post_hash);
        state_hash = post_hash;
        state
    };

    for wasm_bytes in wasm_files.iter() {
        println!("Pre state hash: {:?}", state_hash);
        let result =
            engine_state.run_deploy(&wasm_bytes.bytes, account_addr, timestamp, nonce, state_hash, &gas_limit);
        match result {
            Ok(effects) => {
                let res = engine_state
                    .apply_effect(state_hash, effects.1)
                    .expect(&format!("Error when applying effects."));
                println!(
                    "Result for file {}: Success! New post state hash: {:?}",
                    wasm_bytes.path, res
                );
                state_hash = res; // we need to keep updating the post state hash after each deploy
            }
            Err(_) => println!("Result for file {}: {:?}", wasm_bytes.path, result),
        }
    }
}
