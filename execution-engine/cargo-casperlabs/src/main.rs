//! Command line tool for creating a Wasm contract and tests for use on the CasperLabs network.

#![deny(warnings)]

use std::path::PathBuf;

use clap::{crate_version, App, Arg};
use lazy_static::lazy_static;

pub mod common;
mod contract_package;
mod tests_package;

const APP_NAME: &str = "cargo-casperlabs";
const ABOUT: &str =
    "A command line tool for creating a Wasm contract and tests at <path> for use on the \
     CasperLabs network.";
const TOOLCHAIN: &str = "nightly-2020-01-08";

const PATH_ARG_NAME: &str = "path";
const PATH_ARG_VALUE_NAME: &str = "path";
const PATH_ARG_HELP: &str = "Path to new folder for contract and tests";

const FAILURE_EXIT_CODE: i32 = 101;

lazy_static! {
    static ref USAGE: String = format!(
        r#"cargo casperlabs <path>
    rustup install {0}
    rustup target add --toolchain {0} wasm32-unknown-unknown
    cd <path>/tests
    cargo run"#,
        TOOLCHAIN
    );
    static ref ROOT_PATH: PathBuf = {
        let path_arg = Arg::with_name(PATH_ARG_NAME)
            .required(true)
            .value_name(PATH_ARG_VALUE_NAME)
            .help(PATH_ARG_HELP);

        let arg_matches = App::new(APP_NAME)
            .version(crate_version!())
            .about(ABOUT)
            .usage(USAGE.as_str())
            .arg(path_arg)
            .get_matches();

        arg_matches
            .value_of(PATH_ARG_NAME)
            .expect("expected path")
            .into()
    };
}

fn main() {
    if ROOT_PATH.exists() {
        common::print_error_and_exit(&format!(
            ": destination '{}' already exists",
            ROOT_PATH.display()
        ));
    }

    common::create_dir_all(&*ROOT_PATH);

    contract_package::run_cargo_new();
    contract_package::update_cargo_toml();
    contract_package::add_rust_toolchain();
    contract_package::replace_main_rs();
    contract_package::add_config();

    tests_package::run_cargo_new();
    tests_package::update_cargo_toml();
    tests_package::add_rust_toolchain();
    tests_package::add_build_rs();
    tests_package::replace_main_rs();
}

#[cfg(test)]
mod tests {
    use std::{env, fs};

    use super::TOOLCHAIN;

    #[test]
    fn check_toolchain_version() {
        let mut toolchain_path = env::current_dir().unwrap().display().to_string();
        let index = toolchain_path
            .find("/execution-engine/")
            .expect("test should be run from within execution-engine workspace");
        toolchain_path.replace_range(index + 18.., "rust-toolchain");

        let toolchain_contents =
            fs::read(&toolchain_path).unwrap_or_else(|_| panic!("should read {}", toolchain_path));
        let expected_toolchain_value = String::from_utf8_lossy(&toolchain_contents)
            .trim()
            .to_string();

        // If this fails, ensure `TOOLCHAIN` is updated to match the value in
        // "execution-engine/rust-toolchain".
        assert_eq!(&*expected_toolchain_value, TOOLCHAIN);
    }
}
