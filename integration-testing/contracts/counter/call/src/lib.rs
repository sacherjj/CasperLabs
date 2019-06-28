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
    let hash = ContractPointer::Hash([
        25, 163, 109, 131, 31, 124, 41, 185, 236, 114, 217, 3, 151, 135, 34, 41, 131, 156, 136,
        140, 248, 77, 118, 167, 226, 213, 63, 106, 29, 164, 56, 197,
    ]);
    let arg = "inc";
    let _result: () = call_contract(hash.clone(), &arg, &Vec::new());
    let _value: i32 = {
        let arg = "get";
        call_contract(hash, &arg, &Vec::new())
    };
}
