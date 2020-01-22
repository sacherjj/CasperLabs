#![no_std]

extern crate alloc;

use alloc::string::String;

use contract::contract_api::{runtime, storage};
use types::ApiError;

#[no_mangle]
pub extern "C" fn call() {
    let _ = storage::new_turef(String::from("Hello, World!"));
    runtime::revert(ApiError::User(999))
}
