#![no_std]

extern crate alloc;
extern crate contract_ffi;

use alloc::vec::Vec;

use contract_ffi::contract_api::{runtime, ContractRef, Error};
use contract_ffi::key::Key;
use contract_ffi::unwrap_or_revert::UnwrapOrRevert;

#[no_mangle]
pub extern "C" fn call() {
    let revert_test_uref = runtime::get_key("revert_test").unwrap_or_revert_with(Error::GetKey);

    let contract_ref = match revert_test_uref {
        Key::Hash(hash) => ContractRef::Hash(hash),
        _ => runtime::revert(Error::UnexpectedKeyVariant),
    };

    runtime::call_contract::<_, ()>(contract_ref, &(), &Vec::new());
}
