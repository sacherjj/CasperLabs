#![no_std]
#![feature(alloc)]

extern crate alloc;
extern crate common;

use alloc::collections::btree_map::BTreeMap;
use common::contract_api::{get_caller, store_function, add_uref};
use common::value::account::PublicKey;

#[no_mangle]
pub extern "C" fn get_caller_ext() {
    // Assumes that will be called using account with 
    // public key == '71ba8d2072964fa42794d2752e1fdaac448a25d8943005b4c7128748d855219b'
    // Will fail if we ever change that.
    let caller = get_caller();
    assert!(caller.is_some());
    let expected_caller = PublicKey::new([113, 186, 141, 32, 114, 150, 79, 164, 39, 148, 210, 117, 46, 31, 218, 172, 68, 138, 37, 216, 148, 48, 5, 180, 199, 18, 135, 72, 216, 85, 33, 155]
);
    assert_eq!(caller.unwrap(), expected_caller);
}

#[no_mangle]
pub extern "C" fn call() {
    //  When in the base context there is no caller.
    assert_eq!(get_caller(), None);
    let pointer = store_function("get_caller_ext", BTreeMap::new());
    add_uref("get_caller", &pointer.into());
}
