#![no_std]
#![feature(alloc)]

extern crate alloc;
extern crate common;

use alloc::collections::btree_map::BTreeMap;
use common::contract_api::{get_caller, store_function, add_uref};
use common::value::account::PublicKey;

fn test_get_caller() {
    // Assumes that will be called using test framework genesis account with
    // public key == 'ae7cd84d61ff556806691be61e6ab217791905677adbbe085b8c540d916e8393'
    // Will fail if we ever change that.
    let caller = get_caller();
    let expected_caller = PublicKey::new([174, 124, 216, 77, 97, 255, 85, 104, 6, 105, 27, 230, 30, 106, 178, 23, 121, 25, 5, 103, 122, 219, 190, 8, 91, 140, 84, 13, 145, 110, 131, 147]);
    assert_eq!(caller, expected_caller);
}

#[no_mangle]
pub extern "C" fn get_caller_ext() {
    // works in sub-calls
    test_get_caller();
}

#[no_mangle]
pub extern "C" fn call() {
    // works in session code
    test_get_caller();
    let pointer = store_function("get_caller_ext", BTreeMap::new());
    add_uref("get_caller", &pointer.into());
}
