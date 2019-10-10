#![no_std]

extern crate alloc;

extern crate contract_ffi;

use alloc::vec::Vec;

use contract_ffi::contract_api::{runtime, ContractRef, Error};
use contract_ffi::key::Key;
use contract_ffi::unwrap_or_revert::UnwrapOrRevert;

#[no_mangle]
pub extern "C" fn call() {
    let get_caller_uref = runtime::get_key("get_caller").unwrap_or_revert_with(Error::User(100));
    let pointer = if let Key::Hash(hash) = get_caller_uref {
        ContractRef::Hash(hash)
    } else {
        runtime::revert(Error::User(66))
    };

    // Call `define` part of the contract.
    runtime::call_contract(pointer, &(), &Vec::new())
}
