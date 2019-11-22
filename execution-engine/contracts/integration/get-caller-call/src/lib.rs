#![no_std]

extern crate alloc;

use alloc::vec::Vec;

use contract_ffi::{
    contract_api::{runtime, ContractRef, Error},
    key::Key,
    unwrap_or_revert::UnwrapOrRevert,
};

const GET_CALLER_KEY: &str = "get_caller";

#[no_mangle]
pub extern "C" fn call() {
    let get_caller_uref = runtime::get_key(GET_CALLER_KEY).unwrap_or_revert_with(Error::GetKey);
    let contract_ref = match get_caller_uref {
        Key::Hash(hash) => ContractRef::Hash(hash),
        _ => runtime::revert(Error::UnexpectedKeyVariant),
    };

    // Call `define` part of the contract.
    runtime::call_contract(contract_ref, &(), &Vec::new());
}
