#![no_std]

extern crate alloc;

extern crate contract_ffi;

use alloc::collections::BTreeMap;

use contract_ffi::contract_api::{self, Error};

#[no_mangle]
pub extern "C" fn revert_test_ext() {
    // Call revert with an application specific non-zero exit code.
    // It is 2 because another contract used by test_revert.py calls revert with 1.
    contract_api::runtime::revert(Error::User(2));
}

#[no_mangle]
pub extern "C" fn call() {
    let pointer = contract_api::storage::store_function_at_hash("revert_test_ext", BTreeMap::new());
    contract_api::runtime::put_key("revert_test", &pointer.into())
}
