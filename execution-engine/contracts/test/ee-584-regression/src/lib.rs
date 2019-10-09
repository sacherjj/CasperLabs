#![no_std]

extern crate alloc;

extern crate contract_ffi;

use alloc::string::String;

use contract_ffi::contract_api::{self, Error};

#[no_mangle]
pub extern "C" fn call() {
    let _ = contract_api::storage::new_turef(String::from("Hello, World!"));
    contract_api::runtime::revert(Error::User(999))
}
