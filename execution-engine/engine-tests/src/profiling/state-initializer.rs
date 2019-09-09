//! This executable is designed to be run to set up global state in preparation for running other
//! standalone test executable(s).  This will allow profiling to be done on executables running only
//! meaningful code, rather than including test setup effort in the profile results.

use casperlabs_engine_tests::support::test_support::{
    DeployBuilder, ExecRequestBuilder, LmdbWasmTestBuilder,
};
use clap::{crate_version, App, Arg};
use contract_ffi::value::account::PublicKey;
use contract_ffi::value::U512;
use engine_core::engine_state::MAX_PAYMENT;
use std::{collections::HashMap, env, path::PathBuf, str::FromStr};

const ABOUT: &str = "Initializes global state in preparation for profiling runs.";
const DATA_DIR_ARG_NAME: &str = "data_dir";
const DATA_DIR_ARG_SHORT: &str = "d";
const DATA_DIR_ARG_LONG: &str = "data-dir";
const DATA_DIR_ARG_VALUE_NAME: &str = "PATH";
const DATA_DIR_ARG_HELP: &str = "data_dir";

const GENESIS_ADDR: [u8; 32] = [6u8; 32];
const ACCOUNT_1_ADDR: [u8; 32] = [1u8; 32];
const ACCOUNT_1_AMOUNT: u64 = 1_000_000_000;
const ACCOUNT_2_ADDR: [u8; 32] = [2u8; 32];

const STANDARD_PAYMENT_WASM: &str = "standard_payment.wasm";

fn get_data_dir() -> PathBuf {
    let exe_name = env::current_exe()
        .expect("Expected to read current executable's name")
        .file_stem()
        .expect("Expected a file name for the current executable")
        .to_str()
        .expect("Expected valid unicode for the current executable's name")
        .to_string();
    let arg_matches = App::new(&exe_name)
        .version(crate_version!())
        .about(ABOUT)
        .arg(
            Arg::with_name(DATA_DIR_ARG_NAME)
                .short(DATA_DIR_ARG_SHORT)
                .long(DATA_DIR_ARG_LONG)
                .value_name(DATA_DIR_ARG_VALUE_NAME)
                .help(DATA_DIR_ARG_HELP)
                .takes_value(true),
        )
        .get_matches();
    match arg_matches.value_of(DATA_DIR_ARG_NAME) {
        Some(dir) => PathBuf::from_str(dir).expect("Expected a valid unicode path"),
        None => env::current_dir().expect("Expected to be able to access current working dir"),
    }
}

fn main() {
    let data_dir = get_data_dir();

    let genesis_public_key = PublicKey::new(GENESIS_ADDR);
    let account_1_public_key = PublicKey::new(ACCOUNT_1_ADDR);
    let account_1_amount: U512 = ACCOUNT_1_AMOUNT.into();
    let account_2_public_key = PublicKey::new(ACCOUNT_2_ADDR);

    let exec_request = {
        let deploy = DeployBuilder::new()
            .with_address(GENESIS_ADDR)
            .with_deploy_hash([1; 32])
            .with_session_code(
                "state_initializer.wasm",
                (account_1_public_key, account_1_amount, account_2_public_key),
            )
            .with_payment_code(STANDARD_PAYMENT_WASM, (U512::from(MAX_PAYMENT),))
            .with_authorization_keys(&[genesis_public_key])
            .build();

        ExecRequestBuilder::new().push_deploy(deploy).build()
    };

    let post_state_hash = LmdbWasmTestBuilder::new(&data_dir)
        .run_genesis(GENESIS_ADDR, HashMap::new())
        .exec_with_exec_request(exec_request)
        .expect_success()
        .commit()
        .get_post_state_hash();
    println!("{:?}", post_state_hash);
}
