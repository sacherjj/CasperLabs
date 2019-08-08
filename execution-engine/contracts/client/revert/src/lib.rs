#![no_std]
#![feature(alloc)]

extern crate contract_ffi;

use contract_ffi::contract_api;

#[no_mangle]
pub extern "C" fn call() {
    contract_api::revert(100)
}
