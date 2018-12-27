extern crate clap;
extern crate common;
extern crate execution_engine;
extern crate storage;

use clap::{App, Arg};
use common::key::Key;
use common::value;
use execution_engine::engine::EngineState;
use std::collections::BTreeMap;
use std::fs::File;
use std::io::prelude::*;
use std::iter::Iterator;
use storage::transform::Transform;
use storage::ExecutionEffect;
use storage::{GlobalState, TrackingCopy};

#[derive(Debug)]
struct Task {
    path: String,
    bytes: Vec<u8>,
}

fn main() {
    let default_address = "12345678901234567890";
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
        let mut address = [0u8; 20];
        matches
            .value_of("address")
            .map(|addr| addr.as_bytes())
            .map(|bytes| address.copy_from_slice(bytes))
            .expect("Error when parsing address");
        address
    };

    let mut gs = storage::InMemGS::new();
    prepare_gs(account_addr, &mut gs);
    let mut engine_state = EngineState::new(gs);

    for wasm_bytes in wasm_files.iter() {
        let result = engine_state.run_deploy(&wasm_bytes.bytes, account_addr);
        match result {
            Ok(ExecutionEffect::Success(_, transform_map)) => {
                for (key, transformation) in transform_map.iter() {
                    engine_state
                        .apply_effect(*key, transformation.clone())
                        .expect(&format!("Error when applying effects on {:?}", *key));
                }
                println!("Result for file {}: Success!", wasm_bytes.path);
            }
            Ok(ExecutionEffect::Error) => println!(
                "Result for file {}: ExecutionEffect::Error.",
                wasm_bytes.path
            ),
            Err(_) => println!("Result for file {}: {:?}", wasm_bytes.path, result),
        }
    }
}

// To run, contracts need an existing account.
// This function puts artifical entry to in the GlobalState.
fn prepare_gs<T: TrackingCopy, G: GlobalState<T>>(account_addr: [u8; 20], gs: &mut G) {
    let account = value::Account::new([0u8; 32], 0, BTreeMap::new());
    let transform = Transform::Write(value::Value::Acct(account));
    gs.apply(Key::Account(account_addr), transform).unwrap();
}
