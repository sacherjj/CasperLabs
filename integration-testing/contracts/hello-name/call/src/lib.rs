#![no_std]
#![feature(alloc)]

extern crate alloc;
use alloc::string::String;
use alloc::vec::Vec;

extern crate common;
use common::contract_api::{call_contract, new_uref, add_uref};
use common::contract_api::pointers::ContractPointer;
use common::value::Value;

#[no_mangle]
pub extern "C" fn call() {
    //This hash comes from blake2b256( [0;32] ++ [0;8] ++ [0;4] )
    //let hash = ContractPointer::Hash([164, 102, 153, 51, 236, 214, 169, 167, 126, 44, 250, 247, 179, 214, 203, 229, 239, 69, 145, 25, 5, 153, 113, 55, 255, 188, 176, 201, 7, 4, 42, 100]);
    let hash = ContractPointer::Hash([162, 53, 237, 10, 219, 164, 219, 4, 125, 1, 30, 222, 65, 92, 19, 117, 158, 50, 199, 250, 102, 33, 119, 69, 5, 8, 239, 33, 57, 153, 154, 212]);
    let arg = "World";
    let result: String = call_contract(hash, &arg, &Vec::new());
    assert_eq!("Hello, World", result);

    //store the result at a uref so it can be seen as an effect on the global state
    let uref = new_uref(Value::String(result));
    add_uref("helloworld", &uref.into());

}
