#![no_std]

use contract::{contract_api::runtime, unwrap_or_revert::UnwrapOrRevert};
use types::{ApiError, ContractRef, Key};

const REVERT_TEST_KEY: &str = "revert_test";

#[no_mangle]
pub extern "C" fn call() {
    let revert_test_uref =
        runtime::get_key(REVERT_TEST_KEY).unwrap_or_revert_with(ApiError::GetKey);

    let contract_ref = match revert_test_uref {
        Key::Hash(hash) => ContractRef::Hash(hash),
        _ => runtime::revert(ApiError::UnexpectedKeyVariant),
    };

    runtime::call_contract(contract_ref, ())
}
