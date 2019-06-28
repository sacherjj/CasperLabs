#![no_std]
#![feature(alloc)]

extern crate alloc;
use alloc::string::String;
use alloc::vec::Vec;

extern crate common;
use common::contract_api::pointers::ContractPointer;
use common::contract_api::{add_uref, call_contract, new_uref};
use common::value::Value;

#[no_mangle]
pub extern "C" fn call() {
    //This hash comes from blake2b256( [0;32] ++ [0;8] ++ [0;4] )
    let hash = ContractPointer::Hash([
        40, 187, 84, 61, 149, 153, 87, 11, 8, 127, 115, 154, 177, 24, 64, 119, 110, 49, 39, 103,
        248, 25, 47, 60, 132, 200, 80, 11, 5, 132, 64, 160,
    ]);
    let arg = "World";
    let result: String = call_contract(hash, &arg, &Vec::new());
    assert_eq!("Hello, World", result);

    //store the result at a uref so it can be seen as an effect on the global state
    let uref = new_uref(Value::String(result));
    add_uref("helloworld", &uref.into());
}
