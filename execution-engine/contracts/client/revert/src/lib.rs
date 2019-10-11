#![no_std]

extern crate contract_ffi;

use contract_ffi::contract_api::{runtime, Error};

#[no_mangle]
pub extern "C" fn call() {
    runtime::revert(Error::User(100))
}
