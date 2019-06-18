#![no_std]
#![feature(alloc)]

extern crate alloc;
extern crate common;
use common::contract_api::revert;

#[no_mangle]
pub extern "C" fn call() {
    // Call revert with an application specific non-zero exit code.
    revert(1);
}
