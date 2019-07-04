#![no_std]
#![feature(alloc, cell_update)]

#[macro_use]
extern crate alloc;

extern crate cl_std;

use alloc::collections::btree_map::BTreeMap;
use alloc::string::String;

use cl_std::contract_api;
use cl_std::contract_api::pointers::ContractPointer;

#[no_mangle]
pub extern "C" fn do_nothing() {
    // Doesn't advance RNG of the runtime
    contract_api::ret(&String::from("Hello, world!"), &vec![])
}

#[no_mangle]
pub extern "C" fn do_something() {
    // Advances RNG of the runtime
    let test_string = String::from("Hello, world!");

    let test_uref = contract_api::new_uref(test_string).into();
    contract_api::ret(&test_uref, &vec![test_uref])
}


#[no_mangle]
pub extern "C" fn call() {
    // Export two functions
    let _contract_pointer: ContractPointer = contract_api::store_function("do_nothing", BTreeMap::new());
    let _contract_pointer: ContractPointer = contract_api::store_function("do_something", BTreeMap::new());
}
