extern crate clap;
extern crate common;
extern crate execution_engine;
extern crate shared;
extern crate storage;
extern crate wasm_prep;

use std::fs::File;
use std::io::prelude::*;
use std::iter::Iterator;

use clap::{App, Arg};

use execution_engine::engine::{EngineState, ExecutionResult, RootNotFound};
use execution_engine::execution::WasmiExecutor;
use shared::newtypes::Blake2bHash;
use storage::global_state::inmem::InMemHist;
use storage::history::CommitResult;
use wasm_prep::WasmiPreprocessor;

#[derive(Debug)]
struct Task {
    path: String,
    bytes: Vec<u8>,
}

#[allow(unreachable_code)]
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
            .map(str::as_bytes)
            .map(|bytes| address.copy_from_slice(bytes))
            .expect("Error when parsing address");
        address
    };

    let mut state_hash: Blake2bHash = [0u8; 32].into();

    let gas_limit: u64 = matches
        .value_of("gas-limit")
        .and_then(|v| v.parse::<u64>().ok())
        .expect("Provided gas limit value is not u64.");

    // TODO: move to arg parser
    let timestamp: u64 = 100_000;
    let nonce: u64 = 1;
    let protocol_version: u64 = 1;

    // let path = std::path::Path::new("./tmp/");
    // TODO: Better error handling?
    //    let global_state = LmdbGs::new(&path).unwrap();
    let init_state = storage::global_state::mocked_account(account_addr);
    let global_state = InMemHist::new_initialized(&state_hash, init_state);
    let engine_state = EngineState::new(global_state);

    let wasmi_executor = WasmiExecutor;
    let wasmi_preprocessor: WasmiPreprocessor = Default::default();

    for wasm_bytes in wasm_files.iter() {
        println!("Pre state hash: {:?}", state_hash);
        let result = engine_state.run_deploy(
            &wasm_bytes.bytes,
            &[], // TODO: consume args from CLI
            account_addr,
            timestamp,
            nonce,
            state_hash,
            gas_limit,
            protocol_version,
            &wasmi_executor,
            &wasmi_preprocessor,
        );
        match result {
            Err(RootNotFound(hash)) => println!(
                "Result for file {}: root {:?} not found.",
                wasm_bytes.path, hash
            ),
            Ok(ExecutionResult {
                result: Ok(effects),
                cost,
            }) => {
                println!("Cost of executing the contract was: {}", cost);
                match engine_state.apply_effect(state_hash, effects.1) {
                    Ok(CommitResult::RootNotFound) => println!(
                        "Result for file {}: root {:?} not found.",
                        wasm_bytes.path, state_hash
                    ),
                    Ok(CommitResult::KeyNotFound(key)) => println!(
                        "Result for file {}: key {:?} not found.",
                        wasm_bytes.path, key
                    ),
                    Ok(CommitResult::TypeMismatch(type_mismatch)) => {
                        println!("Result for file {}: {:?}", wasm_bytes.path, type_mismatch)
                    }
                    Ok(CommitResult::Overflow) => println!(
                        "Result for file {}: overflow during addition.",
                        wasm_bytes.path
                    ),
                    Ok(CommitResult::Success(new_root_hash)) => {
                        println!(
                            "Result for file {}: Success! New post state hash: {:?}",
                            wasm_bytes.path, new_root_hash
                        );
                        state_hash = new_root_hash; // we need to keep updating the post state hash after each deploy
                    }
                    Err(storage_err) => eprintln!("Error when applying effects {:?}", storage_err),
                }
            }
            Ok(ExecutionResult {
                result: Err(error),
                cost,
            }) => {
                println!("Gas used during execution: {:?}", cost);
                println!("Result for file {}: {:?}", wasm_bytes.path, error)
            }
        }
    }
}
