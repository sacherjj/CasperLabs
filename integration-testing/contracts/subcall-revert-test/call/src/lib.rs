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
    let revert_test_uref = get_uref("revert_test").unwrap_or_else(|| revert(100));
    let pointer = if let Key::Hash(hash) = revert_test_uref {
        ContractPointer::Hash(hash)
    } else {
        revert(66); // exit code is currently arbitrary
    };

    let _result: () = call_contract(pointer, &(), &Vec::new());
}
