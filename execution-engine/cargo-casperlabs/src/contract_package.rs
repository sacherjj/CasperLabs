//! Consts and functions used to generate the files comprising the "contract" package when running
//! the tool.

use std::path::PathBuf;

use lazy_static::lazy_static;

use crate::{
    common::{self, CL_CONTRACT, CL_TYPES},
    ARGS, TOOLCHAIN,
};

const PACKAGE_NAME: &str = "contract";

const LIB_RS_CONTENTS: &str = r#"#![cfg_attr(not(target_arch = "wasm32"), crate_type = "target arch should be wasm32")]

use casperlabs_contract::{
    contract_api::{runtime, storage},
    unwrap_or_revert::UnwrapOrRevert,
};
use casperlabs_types::{Key, ApiError};

const KEY: &str = "special_value";

fn store(value: String) {
    // Store `value` under a new unforgeable reference.
    let value_ref = storage::new_turef(value);

    // Wrap the unforgeable reference in a value of type `Key`.
    let value_key: Key = value_ref.into();

    // Store this key under the name "special_value" in context-local storage.
    runtime::put_key(KEY, value_key);
}

// All session code must have a `call` entrypoint.
#[no_mangle]
pub extern "C" fn call() {
    // Get the optional first argument supplied to the argument.
    let value: String = runtime::get_arg(0)
        // Unwrap the `Option`, returning an error if there was no argument supplied.
        .unwrap_or_revert_with(ApiError::MissingArgument)
        // Unwrap the `Result` containing the deserialized argument or return an error if there was
        // a deserialization error.
        .unwrap_or_revert_with(ApiError::InvalidArgument);

    store(value);
}
"#;

const CONFIG_CONTENTS: &str = r#"[build]
target = "wasm32-unknown-unknown"
"#;

lazy_static! {
    static ref CARGO_TOML: PathBuf = ARGS.root_path().join(PACKAGE_NAME).join("Cargo.toml");
    static ref RUST_TOOLCHAIN: PathBuf = ARGS.root_path().join(PACKAGE_NAME).join("rust-toolchain");
    static ref MAIN_RS: PathBuf = ARGS.root_path().join(PACKAGE_NAME).join("src/main.rs");
    static ref LIB_RS: PathBuf = ARGS.root_path().join(PACKAGE_NAME).join("src/lib.rs");
    static ref CONFIG: PathBuf = ARGS.root_path().join(PACKAGE_NAME).join(".cargo/config");
    static ref CARGO_TOML_ADDITIONAL_CONTENTS: String = format!(
        r#"{}
{}

[lib]
crate-type = ["cdylib"]
bench = false
doctest = false
test = false

[features]
default = ["casperlabs-contract/std", "casperlabs-types/std"]
"#,
        *CL_CONTRACT, *CL_TYPES,
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

pub fn replace_main_rs() {
    common::remove_file(&*MAIN_RS);
    common::write_file(&*LIB_RS, LIB_RS_CONTENTS);
}

pub fn add_config() {
    let folder = CONFIG.parent().expect("should have parent");
    common::create_dir_all(folder);
    common::write_file(&*CONFIG, CONFIG_CONTENTS);
}
