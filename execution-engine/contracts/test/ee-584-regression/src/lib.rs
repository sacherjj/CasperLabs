#![no_std]

extern crate alloc;
extern crate contract_ffi;

use alloc::string::String;
use contract_ffi::contract_api;

#[no_mangle]
pub extern "C" fn call() {
    let _ = contract_api::new_uref(String::from("Hello, World!"));
    contract_api::revert(999)
}
