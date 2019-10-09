#![no_std]

extern crate alloc;
extern crate contract_ffi;

use alloc::string::String;

use contract_ffi::contract_api::{runtime, storage, Error};

#[no_mangle]
pub extern "C" fn call() {
    let _ = storage::new_turef(String::from("Hello, World!"));
    runtime::revert(Error::User(999))
}
