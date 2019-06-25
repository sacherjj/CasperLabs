#![no_std]
#![feature(alloc)]

extern crate common;
use common::contract_api::call_contract;
use common::contract_api::pointers::ContractPointer;

extern crate alloc;
use alloc::vec::Vec;

#[no_mangle]
pub extern "C" fn call() {
    // nonce == 0 (this is a bug since deploy should be using NEW nonce instead of OLD)
    // https://casperlabs.atlassian.net/browse/EE-384
    // fn_index == 0
    let hash = ContractPointer::Hash([162, 53, 237, 10, 219, 164, 219, 4, 125, 1, 30, 222, 65, 92, 19, 117, 158, 50, 199, 250, 102, 33, 119, 69, 5, 8, 239, 33, 57, 153, 154, 212]);
    // Call `define` part of the contract.
    call_contract(hash, &(), &Vec::new())
}
