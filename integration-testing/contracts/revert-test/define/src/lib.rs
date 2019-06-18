#![no_std]
#![feature(alloc)]

extern crate alloc;
use alloc::collections::BTreeMap;
use alloc::string::String;

extern crate common;
use common::contract_api::*;
use common::key::Key;

#[no_mangle]
pub extern "C" fn revert_test_ext() {
    revert(1);
}

#[no_mangle]
pub extern "C" fn call() {

    //create map of references for stored contract
    let revert_test_urefs: BTreeMap<String, Key> = BTreeMap::new();

    store_function("revert_test_ext", revert_test_urefs);
}
