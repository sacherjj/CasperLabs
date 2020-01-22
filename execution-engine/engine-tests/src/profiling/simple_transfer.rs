//! This executable is designed to be used to profile a single execution of a simple contract which
//! transfers an amount between two accounts.
//!
//! In order to set up the required global state for the transfer, the `state-initializer` should
//! have been run first.
//!
//! By avoiding setting up global state as part of this executable, it will allow profiling to be
//! done only on meaningful code, rather than including test setup effort in the profile results.

use std::{env, io, path::PathBuf};

use base16;
use clap::{crate_version, App, Arg};

use engine_core::engine_state::EngineConfig;
use engine_test_support::low_level::{
    DeployItemBuilder, ExecuteRequestBuilder, LmdbWasmTestBuilder, DEFAULT_PAYMENT,
};
use types::U512;

use casperlabs_engine_tests::profiling;

const ABOUT: &str = "Executes a simple contract which transfers an amount between two accounts.  \
     Note that the 'state-initializer' executable should be run first to set up the required \
     global state.";

const ROOT_HASH_ARG_NAME: &str = "root-hash";
const ROOT_HASH_ARG_VALUE_NAME: &str = "HEX-ENCODED HASH";
const ROOT_HASH_ARG_HELP: &str =
    "Initial root hash; the output of running the 'state-initializer' executable";

const VERBOSE_ARG_NAME: &str = "verbose";
const VERBOSE_ARG_SHORT: &str = "v";
const VERBOSE_ARG_LONG: &str = "verbose";
const VERBOSE_ARG_HELP: &str = "Display the transforms resulting from the contract execution";

const TRANSFER_AMOUNT: u64 = 1;

const STANDARD_PAYMENT_WASM: &str = "standard_payment.wasm";

fn root_hash_arg() -> Arg<'static, 'static> {
    Arg::with_name(ROOT_HASH_ARG_NAME)
        .value_name(ROOT_HASH_ARG_VALUE_NAME)
        .help(ROOT_HASH_ARG_HELP)
}

fn verbose_arg() -> Arg<'static, 'static> {
    Arg::with_name(VERBOSE_ARG_NAME)
        .short(VERBOSE_ARG_SHORT)
        .long(VERBOSE_ARG_LONG)
        .help(VERBOSE_ARG_HELP)
}

fn parse_hash(encoded_hash: &str) -> Vec<u8> {
    base16::decode(encoded_hash).expect("Expected a valid, hex-encoded hash")
}

#[derive(Debug)]
struct Args {
    root_hash: Option<Vec<u8>>,
    data_dir: PathBuf,
    verbose: bool,
}

impl Args {
    fn new() -> Self {
        let exe_name = profiling::exe_name();
        let data_dir_arg = profiling::data_dir_arg();
        let arg_matches = App::new(&exe_name)
            .version(crate_version!())
            .about(ABOUT)
            .arg(root_hash_arg())
            .arg(data_dir_arg)
            .arg(verbose_arg())
            .get_matches();
        let root_hash = arg_matches.value_of(ROOT_HASH_ARG_NAME).map(parse_hash);
        let data_dir = profiling::data_dir(&arg_matches);
        let verbose = arg_matches.is_present(VERBOSE_ARG_NAME);
        Args {
            root_hash,
            data_dir,
            verbose,
        }
    }
}

fn main() {
    let args = Args::new();

    // If the required initial root hash wasn't passed as a command line arg, expect to read it in
    // from stdin to allow for it to be piped from the output of 'state-initializer'.
    let root_hash = args.root_hash.unwrap_or_else(|| {
        let mut input = String::new();
        let _ = io::stdin().read_line(&mut input);
        parse_hash(input.trim_end())
    });

    let account_1_public_key = profiling::account_1_public_key();
    let account_2_public_key = profiling::account_2_public_key();

    let exec_request = {
        let deploy = DeployItemBuilder::new()
            .with_address(account_1_public_key.value())
            .with_deploy_hash([1; 32])
            .with_session_code(
                "simple_transfer.wasm",
                (account_2_public_key, U512::from(TRANSFER_AMOUNT)),
            )
            .with_payment_code(STANDARD_PAYMENT_WASM, (*DEFAULT_PAYMENT,))
            .with_authorization_keys(&[account_1_public_key])
            .build();

        ExecuteRequestBuilder::new().push_deploy(deploy).build()
    };

    let mut test_builder =
        LmdbWasmTestBuilder::open(&args.data_dir, EngineConfig::new(), root_hash);

    test_builder.exec(exec_request).expect_success().commit();

    if args.verbose {
        println!("{:#?}", test_builder.get_transforms());
    }
}
