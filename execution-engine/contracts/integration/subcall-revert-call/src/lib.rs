#![no_std]

use contract::{
    contract_api::{runtime, ContractRef, Error},
    key::Key,
    unwrap_or_revert::UnwrapOrRevert,
};

const REVERT_TEST_KEY: &str = "revert_test";

#[no_mangle]
pub extern "C" fn call() {
    let revert_test_uref = runtime::get_key(REVERT_TEST_KEY).unwrap_or_revert_with(Error::GetKey);

    let contract_ref = match revert_test_uref {
        Key::Hash(hash) => ContractRef::Hash(hash),
        _ => runtime::revert(Error::UnexpectedKeyVariant),
    };

    runtime::call_contract(contract_ref, ())
}
