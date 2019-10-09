#![no_std]

extern crate contract_ffi;
extern crate local_state;

use contract_ffi::contract_api;
use contract_ffi::contract_api::Error;
use contract_ffi::unwrap_or_revert::UnwrapOrRevert;

const ENTRY_FUNCTION_NAME: &str = "delegate";
const CONTRACT_NAME: &str = "local_state_stored";

#[no_mangle]
pub extern "C" fn delegate() {
    local_state::delegate()
}

#[no_mangle]
pub extern "C" fn call() {
    let key = contract_api::storage::store_function(ENTRY_FUNCTION_NAME, Default::default())
        .into_turef()
        .unwrap_or_revert_with(Error::UnexpectedContractPointerVariant)
        .into();

    contract_api::runtime::put_key(CONTRACT_NAME, &key);
}
