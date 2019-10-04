#![no_std]

extern crate alloc;
extern crate contract_ffi;

use alloc::vec::Vec;
use contract_ffi::contract_api::pointers::ContractPointer;
use contract_ffi::contract_api::{call_contract, get_key, revert, Error};
use contract_ffi::key::Key;

#[no_mangle]
pub extern "C" fn call() {
    let get_caller_uref = get_key("get_caller").unwrap_or_else(|| revert(Error::User(100)));
    let pointer = if let Key::Hash(hash) = get_caller_uref {
        ContractPointer::Hash(hash)
    } else {
        revert(Error::User(66))
    };

    // Call `define` part of the contract.
    call_contract(pointer, &(), &Vec::new())
}
