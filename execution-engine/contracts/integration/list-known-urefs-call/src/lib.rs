#![no_std]

extern crate alloc;

extern crate contract_ffi;

use alloc::vec::Vec;

use contract_ffi::contract_api::pointers::ContractPointer;
use contract_ffi::contract_api::{self, Error};
use contract_ffi::key::Key;
use contract_ffi::unwrap_or_revert::UnwrapOrRevert;

#[no_mangle]
pub extern "C" fn call() {
    let list_named_keys_key =
        contract_api::runtime::get_key("list_named_keys").unwrap_or_revert_with(Error::User(100));
    let pointer = if let Key::Hash(hash) = list_named_keys_key {
        ContractPointer::Hash(hash)
    } else {
        contract_api::runtime::revert(Error::User(66)); // exit code is currently arbitrary
    };

    // Call `define` part of the contract.
    contract_api::runtime::call_contract(pointer, &(), &Vec::new())
}
