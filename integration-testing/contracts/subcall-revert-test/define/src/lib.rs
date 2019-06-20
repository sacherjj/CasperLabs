#![no_std]
#![feature(alloc)]

extern crate alloc;
use alloc::collections::BTreeMap;

extern crate common;
use common::contract_api::*;


#[no_mangle]
pub extern "C" fn revert_test_ext() {
    // Call revert with an application specific non-zero exit code.
    // It is 2 because another contract used by test_revert.py calls revert with 1.
    revert(2);
}

#[no_mangle]
pub extern "C" fn call() {
    store_function("revert_test_ext", BTreeMap::new());
}
