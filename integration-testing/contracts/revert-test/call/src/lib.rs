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
    //                                  pk    ++ nonce ++ function_counter 
    // nonce=0, counter=0

    let hash = ContractPointer::Hash([164, 102, 153, 51, 236, 214, 169, 167, 126, 44, 250, 247, 179, 214, 203, 229, 239, 69, 145, 25, 5, 153, 113, 55, 255, 188, 176, 201, 7, 4, 42, 100]);
    // TODO: Once https://casperlabs.atlassian.net/browse/EE-384 is fixed the above lines should be replaced with:

    // // nonce=1 counter=0 
    // let hash = ContractPointer::Hash([40, 187, 84, 61, 149, 153, 87, 11, 8, 127, 115, 154, 177, 24, 64, 119, 110, 49, 39, 103, 248, 25, 47, 60, 132, 200, 80, 11, 5, 132, 64, 160]);

    let _result: () = call_contract(hash, &(), &Vec::new());
}
