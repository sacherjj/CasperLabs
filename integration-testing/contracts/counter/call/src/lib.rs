#![no_std]
#![feature(alloc)]

extern crate alloc;
use alloc::vec::Vec;

extern crate common;
use common::contract_api::{call_contract, revert, get_uref};
use common::contract_api::pointers::ContractPointer;
use common::key::Key;

#[no_mangle]
pub extern "C" fn call() {
    let counter_uref = get_uref("counter").unwrap_or_else(|| revert(100));
    let pointer = if let Key::Hash(hash) = counter_uref {
        ContractPointer::Hash(hash)
    } else {
        revert(66)
    };

    let _result: () = {
        let arg = "inc";
        call_contract( pointer.clone(), &arg, &Vec::new())
    };

    let _value: i32 = {
        let arg = "get";
        call_contract(pointer, &arg, &Vec::new())
    };
}
