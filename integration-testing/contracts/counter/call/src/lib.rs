#![no_std]
#![feature(alloc)]

extern crate alloc;
use alloc::vec::Vec;

extern crate common;
use common::contract_api::call_contract;
use common::contract_api::pointers::ContractPointer;

#[no_mangle]
pub extern "C" fn call() {
    //This hash comes from blake2b256( [0;32] ++ [0;8] ++ [0;4] )
    // let hash = ContractPointer::Hash([201, 123, 219, 72, 220, 218, 196, 8, 153, 155, 66, 213, 0, 56,26, 117, 58, 115, 205, 209, 96, 73, 89, 3, 7, 155, 124, 250,90, 64, 33, 161]);
    let hash = ContractPointer::Hash([29, 27, 5, 148, 75, 240, 33, 242, 243, 121, 57, 135, 155, 29, 178, 67, 8, 35, 140, 220, 73, 150, 63, 52, 108, 119, 110, 231, 6, 211, 170, 213]);
    let arg = "inc";
    let _result: () = call_contract(hash.clone(), &arg, &Vec::new());
    let _value: i32 = {
        let arg = "get";
        call_contract(hash, &arg, &Vec::new())
    };
}
