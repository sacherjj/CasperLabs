#![no_std]

extern crate alloc;
extern crate contract_ffi;

use alloc::collections::BTreeMap;

use contract_ffi::contract_api::{runtime, storage, Error};
use contract_ffi::unwrap_or_revert::UnwrapOrRevert;

const ENTRY_FUNCTION_NAME: &str = "delegate";
const CONTRACT_NAME: &str = "do_nothing_stored";

#[no_mangle]
pub extern "C" fn delegate() {}

#[no_mangle]
pub extern "C" fn call() {
    let key = storage::store_function(ENTRY_FUNCTION_NAME, BTreeMap::new())
        .into_turef()
        .unwrap_or_revert_with(Error::UnexpectedContractPointerVariant)
        .into();

    runtime::put_key(CONTRACT_NAME, &key);
}
