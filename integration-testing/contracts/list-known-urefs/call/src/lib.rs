#![no_std]
#![feature(alloc)]

extern crate common;
use common::contract_api::call_contract;
use common::contract_api::pointers::ContractPointer;

extern crate alloc;
use alloc::vec::Vec;

#[no_mangle]
pub extern "C" fn call() {
    // Assumes that `define` contract was deployed with
    // address == 303030...
    // nonce == 0 (this is a bug since deploy should be using NEW nonce instead of OLD)
    // https://casperlabs.atlassian.net/browse/EE-384
    // fn_index == 1
    let hash = ContractPointer::Hash([
        40, 187, 84, 61, 149, 153, 87, 11, 8, 127, 115, 154, 177, 24, 64, 119, 110, 49, 39, 103,
        248, 25, 47, 60, 132, 200, 80, 11, 5, 132, 64, 160,
    ]);
    // Call `define` part of the contract.
    call_contract(hash, &(), &Vec::new())
}
