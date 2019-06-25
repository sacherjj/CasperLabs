#![no_std]
#![feature(alloc)]

extern crate alloc;
use alloc::vec::Vec;

extern crate common;
use common::contract_api::call_contract;
use common::contract_api::pointers::ContractPointer;

#[no_mangle]
pub extern "C" fn call() {

    // Call function stored by another contract (sub-call)

    //This hash comes from blake2b256( [0;32] ++ [0;8] ++ [0;4] )
    //                                  pk    ++ nonce ++ function_counter
    // nonce=0, counter=0

    // This is a value for the 30303.. account
    // let hash = ContractPointer::Hash([164, 102, 153, 51, 236, 214, 169, 167, 126, 44, 250, 247, 179, 214, 203, 229, 239, 69, 145, 25, 5, 153, 113, 55, 255, 188, 176, 201, 7, 4, 42, 100]);
    // And this one is for the key the integration tests are using:
    let hash = ContractPointer::Hash([162, 53, 237, 10, 219, 164, 219, 4, 125, 1, 30, 222, 65, 92, 19, 117, 158, 50, 199, 250, 102, 33, 119, 69, 5, 8, 239, 33, 57, 153, 154, 212]);

    // TODO: Once https://casperlabs.atlassian.net/browse/EE-384 is fixed the above lines should be replaced with one based on nonce=1

    let _result: () = call_contract(hash, &(), &Vec::new());
}
