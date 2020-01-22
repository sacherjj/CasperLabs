use std::{env, path::PathBuf, str::FromStr};

use clap::{Arg, ArgMatches};

use types::{account::PublicKey, U512};

const DATA_DIR_ARG_NAME: &str = "data-dir";
const DATA_DIR_ARG_SHORT: &str = "d";
const DATA_DIR_ARG_LONG: &str = "data-dir";
const DATA_DIR_ARG_VALUE_NAME: &str = "PATH";
const DATA_DIR_ARG_HELP: &str = "Directory in which persistent data is stored [default: current \
                                 working directory]";

const ACCOUNT_1_ADDR: [u8; 32] = [1u8; 32];
const ACCOUNT_1_INITIAL_AMOUNT: u64 = 1_000_000_000;
const ACCOUNT_2_ADDR: [u8; 32] = [2u8; 32];

pub fn exe_name() -> String {
    env::current_exe()
        .expect("Expected to read current executable's name")
        .file_stem()
        .expect("Expected a file name for the current executable")
        .to_str()
        .expect("Expected valid unicode for the current executable's name")
        .to_string()
}

pub fn data_dir_arg() -> Arg<'static, 'static> {
    Arg::with_name(DATA_DIR_ARG_NAME)
        .short(DATA_DIR_ARG_SHORT)
        .long(DATA_DIR_ARG_LONG)
        .value_name(DATA_DIR_ARG_VALUE_NAME)
        .help(DATA_DIR_ARG_HELP)
        .takes_value(true)
}

pub fn data_dir(arg_matches: &ArgMatches) -> PathBuf {
    match arg_matches.value_of(DATA_DIR_ARG_NAME) {
        Some(dir) => PathBuf::from_str(dir).expect("Expected a valid unicode path"),
        None => env::current_dir().expect("Expected to be able to access current working dir"),
    }
}

pub fn account_1_public_key() -> PublicKey {
    PublicKey::new(ACCOUNT_1_ADDR)
}

pub fn account_1_initial_amount() -> U512 {
    ACCOUNT_1_INITIAL_AMOUNT.into()
}

pub fn account_2_public_key() -> PublicKey {
    PublicKey::new(ACCOUNT_2_ADDR)
}
