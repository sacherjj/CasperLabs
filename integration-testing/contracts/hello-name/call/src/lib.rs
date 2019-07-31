#![no_std]
#![feature(alloc)]

extern crate alloc;
extern crate common;

use alloc::string::String;
use alloc::vec::Vec;
use common::contract_api::pointers::ContractPointer;
use common::contract_api::{add_uref, call_contract, get_uref, new_uref, revert};
use common::key::Key;
use common::value::Value;

#[no_mangle]
pub extern "C" fn call() {

    let hello_name_uref = get_uref("hello_name").unwrap_or_else(|| revert(100));
    let pointer = if let Key::Hash(hash) = hello_name_uref {
        ContractPointer::Hash(hash)
    } else {
        revert(66); // exit code is currently arbitrary
    };

    let arg = "World";
    let result: String = call_contract(pointer, &arg, &Vec::new());
    assert_eq!("Hello, World", result);

    //store the result at a uref so it can be seen as an effect on the global state
    let uref = new_uref(Value::String(result));
    add_uref("helloworld", &uref.into());
}
