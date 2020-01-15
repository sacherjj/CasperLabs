//! Consts and functions used to generate the files comprising the "tests" package when running the
//! tool.

use std::path::PathBuf;

use lazy_static::lazy_static;

use crate::{
    common::{self, CL_CONTRACT, CL_TYPES},
    dependency::Dependency,
    ARGS, TOOLCHAIN,
};

const PACKAGE_NAME: &str = "tests";

const INTEGRATION_TESTS_RS_CONTENTS: &str = r#"// import KEY constant

const MY_ACCOUNT: [u8; 32] = [7u8; 32];
const KEY: &str = "special_value";

fn check_value(value: Value) -> bool {
    // ...
}

fn main() {
    let mut context = TestContextBuilder::new()
        .with_account(MY_ACCOUNT, U512::from(128_000_000))
        .build();

    let session = SessionBuilder::new()
        .with_address(MY_ACCOUNT)
        .with_session_code("my_session.wasm", ("hello world",))
        .build();

    let query = QueryBuilder::new()
        .with_base_key(MY_ACCOUNT)
        .with_path([KEY])
        .build();

    let result_of_query: Result<Value, Error> = context
        .run(session)
        .query(query);

    let value = result_of_query.expect("should be a value");

    assert!(check_value(value));
}

#[test]
fn warn_about_running_cargo_test() {
    panic!("Execute \"cargo run\" to test the contract, not \"cargo test\".");
}
"#;

const BUILD_RS_CONTENTS: &str = r#"use std::{env, fs, path::PathBuf, process::Command};

const CONTRACT_ROOT: &str = "../contract";
const CONTRACT_CARGO_TOML: &str = "../contract/Cargo.toml";
const CONTRACT_LIB_RS: &str = "../contract/src/lib.rs";
const BUILD_ARGS: [&str; 2] = ["build", "--release"];
const WASM_FILENAME: &str = "contract.wasm";
const ORIGINAL_WASM_DIR: &str = "../contract/target/wasm32-unknown-unknown/release";
const NEW_WASM_DIR: &str = "target/wasm";

fn main() {
    // Watch contract source files for changes.
    println!("cargo:rerun-if-changed={}", CONTRACT_CARGO_TOML);
    println!("cargo:rerun-if-changed={}", CONTRACT_LIB_RS);

    // Build the contract.
    let output = Command::new("cargo")
        .current_dir(CONTRACT_ROOT)
        .args(&BUILD_ARGS)
        .output()
        .expect("Expected to build Wasm contracts");
    assert!(
        output.status.success(),
        "Failed to build Wasm contracts:\n{:?}",
        output
    );

    // Move the compiled Wasm file to our own build folder ("target/wasm/contract.wasm").
    let new_wasm_dir = env::current_dir().unwrap().join(NEW_WASM_DIR);
    let _ = fs::create_dir(&new_wasm_dir);

    let original_wasm_file = PathBuf::from(ORIGINAL_WASM_DIR).join(WASM_FILENAME);
    let copied_wasm_file = new_wasm_dir.join(WASM_FILENAME);
    fs::copy(original_wasm_file, copied_wasm_file).unwrap();
}
"#;

lazy_static! {
    static ref CARGO_TOML: PathBuf = ARGS.root_path().join(PACKAGE_NAME).join("Cargo.toml");
    static ref RUST_TOOLCHAIN: PathBuf = ARGS.root_path().join(PACKAGE_NAME).join("rust-toolchain");
    static ref BUILD_RS: PathBuf = ARGS.root_path().join(PACKAGE_NAME).join("build.rs");
    static ref MAIN_RS: PathBuf = ARGS.root_path().join(PACKAGE_NAME).join("src/main.rs");
    static ref INTEGRATION_TESTS_RS: PathBuf = ARGS
        .root_path()
        .join(PACKAGE_NAME)
        .join("src/integration_tests.rs");
    static ref ENGINE_TEST_SUPPORT: Dependency = Dependency::new(
        "casperlabs-engine-test-support",
        "0.1.0",
        "engine-test-support"
    );
    static ref CARGO_TOML_ADDITIONAL_CONTENTS: String = format!(
        r#"{}
{}
{}

[[bin]]
name = "integration-tests"
path = "src/integration_tests.rs"

[features]
default = ["casperlabs-contract/std", "casperlabs-types/std"]
"#,
        *CL_CONTRACT, *CL_TYPES, *ENGINE_TEST_SUPPORT,
    );
}

pub fn run_cargo_new() {
    common::run_cargo_new(PACKAGE_NAME);
}

pub fn update_cargo_toml() {
    common::append_to_file(&*CARGO_TOML, &*CARGO_TOML_ADDITIONAL_CONTENTS);
}

pub fn add_rust_toolchain() {
    common::write_file(&*RUST_TOOLCHAIN, format!("{}\n", TOOLCHAIN));
}

pub fn add_build_rs() {
    common::write_file(&*BUILD_RS, BUILD_RS_CONTENTS);
}

pub fn replace_main_rs() {
    common::remove_file(&*MAIN_RS);
    common::write_file(&*INTEGRATION_TESTS_RS, INTEGRATION_TESTS_RS_CONTENTS);
}

#[cfg(test)]
pub mod tests {
    use super::*;

    const ENGINE_TEST_SUPPORT_TOML_PATH: &str = "engine-test-support/Cargo.toml";

    #[test]
    fn check_engine_test_support_version() {
        common::tests::check_package_version(&*ENGINE_TEST_SUPPORT, ENGINE_TEST_SUPPORT_TOML_PATH);
    }
}
