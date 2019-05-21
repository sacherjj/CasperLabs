extern crate clap;
extern crate common;
extern crate execution_engine;
#[macro_use]
extern crate lazy_static;
extern crate shared;
extern crate storage;
extern crate wasm_prep;

use std::collections::BTreeMap;
use std::fs::File;
use std::io::prelude::*;
use std::iter::Iterator;

use clap::{App, Arg, ArgMatches};

use execution_engine::engine::{EngineState, ExecutionResult, RootNotFound};
use execution_engine::execution::WasmiExecutor;
use shared::logging;
use shared::logging::log_level::LogLevel;
use shared::logging::log_settings::{LogLevelFilter, LogSettings};
use shared::logging::{log_level, log_settings};
use shared::newtypes::Blake2bHash;
use storage::global_state::in_memory::InMemoryGlobalState;
use storage::history::CommitResult;
use wasm_prep::{wasm_costs::WasmCosts, WasmiPreprocessor};

// exe / proc
const PROC_NAME: &str = "execution-engine";
const APP_NAME: &str = "Execution Engine Standalone";
const SERVER_START_MESSAGE: &str = "starting Execution Engine Standalone";
const SERVER_STOP_MESSAGE: &str = "stopping Execution Engine Standalone";
const SERVER_NO_WASM_MESSAGE: &str = "no wasm files to process";
const SERVER_NO_GAS_MESSAGE: &str = "no gas available";

// loglevel
const ARG_LOG_LEVEL: &str = "loglevel";
const ARG_LOG_LEVEL_VALUE: &str = "LOGLEVEL";
const ARG_LOG_LEVEL_HELP: &str = "[ fatal | error | warning | info | debug ]";

// defaults
const DEFAULT_ADDRESS: &str = "00000000000000000000";
const DEFAULT_GAS_LIMIT: &str = "18446744073709551615";

// Command line arguments instance
lazy_static! {
    static ref ARG_MATCHES: clap::ArgMatches<'static> = get_args();
}

// LogSettings instance to be used within this application
lazy_static! {
    static ref LOG_SETTINGS: log_settings::LogSettings = get_log_settings();
}

#[derive(Debug)]
struct Task {
    path: String,
    bytes: Vec<u8>,
}

#[allow(unreachable_code)]
fn main() {
    set_panic_hook();

    log_settings::set_log_settings_provider(&*LOG_SETTINGS);

    log_server_info(SERVER_START_MESSAGE);

    let matches: &clap::ArgMatches = &*ARG_MATCHES;

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

    if wasm_files.is_empty() {
        log_server_info(SERVER_NO_WASM_MESSAGE);
    }

    let account_addr: [u8; 20] = {
        let mut address = [48u8; 20];
        matches
            .value_of("address")
            .map(str::as_bytes)
            .map(|bytes| address.copy_from_slice(bytes))
            .expect("Error when parsing address");
        address
    };

    let gas_limit: u64 = matches
        .value_of("gas-limit")
        .and_then(|v| v.parse::<u64>().ok())
        .expect("Provided gas limit value is not u64.");

    if gas_limit == 0 {
        log_server_info(SERVER_NO_GAS_MESSAGE);
    }

    // TODO: move to arg parser
    let timestamp: u64 = 100_000;
    let nonce: u64 = 1;
    let protocol_version: u64 = 1;

    // let path = std::path::Path::new("./tmp/");
    // TODO: Better error handling?
    //    let global_state = LmdbGs::new(&path).unwrap();
    let init_state = storage::global_state::mocked_account(account_addr);
    let global_state =
        InMemoryGlobalState::from_pairs(&init_state).expect("Could not create global state");
    let mut state_hash: Blake2bHash = global_state.root_hash;
    let engine_state = EngineState::new(global_state);

    let wasmi_executor = WasmiExecutor;
    let wasm_costs = WasmCosts::from_version(protocol_version).unwrap_or_else(|| {
        panic!(
            "Wasm cost table wasn't defined for protocol version: {}",
            protocol_version
        )
    });
    let wasmi_preprocessor: WasmiPreprocessor = WasmiPreprocessor::new(wasm_costs);

    for wasm_bytes in wasm_files.iter() {
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

        let mut log_level = LogLevel::Info;
        let mut properties: BTreeMap<String, String> = BTreeMap::new();
        let mut error_message: String = String::new();

        properties.insert(String::from("pre-state-hash"), format!("{:?}", state_hash));
        properties.insert(String::from("wasm-path"), wasm_bytes.path.to_owned());

        match result {
            Err(RootNotFound(hash)) => {
                log_level = LogLevel::Error;
                error_message = format!("root {:?} not found", hash);
                properties.insert(String::from("root-hash"), format!("{:?}", hash));
            }
            Ok(ExecutionResult {
                result: Ok(effects),
                cost,
            }) => {
                properties.insert("gas-cost".to_string(), format!("{:?}", cost));

                match engine_state.apply_effect(state_hash, effects.1) {
                    Ok(CommitResult::RootNotFound) => {
                        log_level = LogLevel::Warning;
                        error_message = format!("root {:?} not found", state_hash);
                        properties.insert(String::from("root-hash"), format!("{:?}", state_hash));
                    }
                    Ok(CommitResult::KeyNotFound(key)) => {
                        log_level = LogLevel::Warning;
                        error_message = format!("key {:?} not found", key);
                    }
                    Ok(CommitResult::TypeMismatch(type_mismatch)) => {
                        log_level = LogLevel::Warning;
                        error_message = format!("type mismatch: {:?} ", type_mismatch);
                    }
                    Ok(CommitResult::Overflow) => {
                        log_level = LogLevel::Warning;
                        error_message = String::from("overflow during addition");
                    }
                    Ok(CommitResult::Success(new_root_hash)) => {
                        state_hash = new_root_hash; // we need to keep updating the post state hash after each deploy
                        properties.insert(
                            String::from("post-state-hash"),
                            format!("{:?}", new_root_hash),
                        );
                    }
                    Err(storage_err) => {
                        error_message = format!("{:?}", storage_err);
                    }
                }
            }
            Ok(ExecutionResult {
                result: Err(error),
                cost,
            }) => {
                properties.insert("gas-cost".to_string(), format!("{:?}", cost));
                error_message = format!("{:?}", error);
            }
        }

        let success = error_message.is_empty();
        properties.insert(String::from("success"), format!("{:?}", success));

        if !success {
            properties.insert(String::from("error"), error_message);
        }

        let message_format: String = if success {
            String::from("{pre-state-hash} {gas-cost} {wasm-path} success: {success}")
        } else {
            String::from(
                "{pre-state-hash} {gas-cost} {wasm-path} success: {success} error: {error}",
            )
        };

        logging::log_props(log_level, message_format, properties);
    }

    log_server_info(SERVER_STOP_MESSAGE);
}

/// Sets panic hook for logging panic info
fn set_panic_hook() {
    let hook: Box<dyn Fn(&std::panic::PanicInfo) + 'static + Sync + Send> =
        Box::new(move |panic_info| {
            match panic_info.payload().downcast_ref::<&str>() {
                Some(s) => {
                    let panic_message = format!("{:?}", s);
                    logging::log(log_level::LogLevel::Fatal, &panic_message);
                }
                None => {
                    let panic_message = format!("{:?}", panic_info);
                    logging::log(log_level::LogLevel::Fatal, &panic_message);
                }
            }

            log_server_info(SERVER_STOP_MESSAGE);
        });
    std::panic::set_hook(hook);
}

/// Gets command line arguments
fn get_args() -> ArgMatches<'static> {
    App::new(APP_NAME)
        .arg(
            Arg::with_name("address")
                .short("a")
                .long("address")
                .default_value(DEFAULT_ADDRESS)
                .value_name("BYTES")
                .required(false)
                .takes_value(true),
        )
        .arg(
            Arg::with_name("gas-limit")
                .short("l")
                .long("gas-limit")
                .default_value(DEFAULT_GAS_LIMIT)
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
        .arg(
            Arg::with_name(ARG_LOG_LEVEL)
                .required(false)
                .long(ARG_LOG_LEVEL)
                .takes_value(true)
                .value_name(ARG_LOG_LEVEL_VALUE)
                .help(ARG_LOG_LEVEL_HELP),
        )
        .get_matches()
}

/// Builds and returns log_settings
fn get_log_settings() -> log_settings::LogSettings {
    let matches: &clap::ArgMatches = &*ARG_MATCHES;

    let log_level_filter = LogLevelFilter::from_input(matches.value_of(ARG_LOG_LEVEL));

    LogSettings::new(PROC_NAME, log_level_filter)
}

/// Logs server status info messages
fn log_server_info(message: &str) {
    logging::log(log_level::LogLevel::Info, message);
}
