#![no_std]
#![feature(alloc, cell_update)]

extern crate alloc;
extern crate cl_std;

use alloc::collections::btree_map::BTreeMap;
use alloc::prelude::Vec;
use cl_std::contract_api;
use cl_std::value::account::PublicKey;

#[no_mangle]
pub extern "C" fn check_caller_ext() {
    let caller_public_key: PublicKey = contract_api::get_caller();
    contract_api::ret(&caller_public_key, &Vec::new())
}

#[no_mangle]
pub extern "C" fn call() {
    let known_public_key: PublicKey = contract_api::get_arg(0);
    let caller_public_key: PublicKey = contract_api::get_caller();
    assert_eq!(
        caller_public_key, known_public_key,
        "caller public key was not known public key"
    );

    let pointer = contract_api::store_function("check_caller_ext", BTreeMap::new());
    let subcall_public_key: PublicKey = contract_api::call_contract(pointer, &(), &Vec::new());
    assert_eq!(
        subcall_public_key, known_public_key,
        "subcall public key was not known public key"
    );
}
