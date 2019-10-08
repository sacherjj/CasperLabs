#![no_std]

extern crate alloc;
extern crate contract_ffi;

use alloc::vec::Vec;
use contract_ffi::contract_api::pointers::ContractPointer;
use contract_ffi::contract_api::{call_contract, get_key, revert, Error};
use contract_ffi::key::Key;

#[no_mangle]
pub extern "C" fn call() {
    let list_named_keys_key =
        get_key("list_named_keys").unwrap_or_else(|| revert(Error::User(100)));
    let pointer = if let Key::Hash(hash) = list_named_keys_key {
        ContractPointer::Hash(hash)
    } else {
        revert(Error::User(66)); // exit code is currently arbitrary
    };

    // Call `define` part of the contract.
    call_contract(pointer, &(), &Vec::new())
}
