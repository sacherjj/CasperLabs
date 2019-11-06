#![no_std]

extern crate alloc;

use alloc::vec;

use contract_ffi::contract_api::{runtime, storage, Error};
use contract_ffi::unwrap_or_revert::UnwrapOrRevert;

const MINT_FUNCTION_NAME: &str = "mint_ext";

#[no_mangle]
pub extern "C" fn mint_ext() {
    mint_token::delegate();
}

#[no_mangle]
pub extern "C" fn call() {
    let uref = storage::store_function(MINT_FUNCTION_NAME, Default::default())
        .into_turef()
        .unwrap_or_revert_with(Error::UnexpectedContractRefVariant)
        .into();

    runtime::ret(uref, vec![uref]);
}
