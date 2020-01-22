#![no_std]

extern crate alloc;

use alloc::collections::BTreeMap;

use contract::contract_api::{runtime, storage};
use types::ApiError;

const REVERT_TEST_EXT: &str = "revert_test_ext";
const REVERT_TEST_KEY: &str = "revert_test";

#[no_mangle]
pub extern "C" fn revert_test_ext() {
    // Call revert with an application specific non-zero exit code.
    // It is 2 because another contract used by test_revert.py calls revert with 1.
    runtime::revert(ApiError::User(2));
}

#[no_mangle]
pub extern "C" fn call() {
    let pointer = storage::store_function_at_hash(REVERT_TEST_EXT, BTreeMap::new());
    runtime::put_key(REVERT_TEST_KEY, pointer.into())
}
