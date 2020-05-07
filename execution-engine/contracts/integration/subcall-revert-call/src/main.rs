#![no_std]
#![no_main]

use contract::{contract_api::runtime, unwrap_or_revert::UnwrapOrRevert};
use types::ApiError;

const REVERT_TEST_KEY: &str = "revert_test";

#[no_mangle]
pub extern "C" fn call() {
    let revert_test_uref =
        runtime::get_key(REVERT_TEST_KEY).unwrap_or_revert_with(ApiError::GetKey);

    runtime::call_contract(revert_test_uref, ())
}
