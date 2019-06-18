#![no_std]
#![feature(alloc)]

extern crate alloc;

extern crate common;
use common::contract_api::*;

#[no_mangle]
pub extern "C" fn call() {
    // Call revert with an application specific non-zero exit code. It is 2 to make it different from 1 used
    // by another contract called by test_revert.py.
    revert(2);
}
