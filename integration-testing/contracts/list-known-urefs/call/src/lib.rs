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
    let pointer = if let Key::Hash(hash) = get_uref("list_known_urefs") {
        ContractPointer::Hash(hash)
    } else {
        revert(66); // exit code is currently arbitrary
    };

    // Call `define` part of the contract.
    call_contract(pointer, &(), &Vec::new())
}
