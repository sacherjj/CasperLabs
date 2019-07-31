#![no_std]
#![feature(alloc)]

extern crate alloc;
extern crate common;

use alloc::vec::Vec;
use common::contract_api::pointers::ContractPointer;
use common::contract_api::{call_contract, get_uref, revert};
use common::key::Key;

#[no_mangle]
pub extern "C" fn call() {
    let get_caller_uref = get_uref("get_caller").unwrap_or_else(|| revert(100));
    let pointer = if let Key::Hash(hash) = get_caller_uref {
        ContractPointer::Hash(hash)
    } else {
        revert(66)
    };

    // Call `define` part of the contract.
    call_contract(pointer, &(), &Vec::new())
}
