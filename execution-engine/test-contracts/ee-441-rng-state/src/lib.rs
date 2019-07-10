#![no_std]
#![feature(alloc, cell_update)]

#[macro_use]
extern crate alloc;
extern crate cl_std;

use alloc::collections::btree_map::BTreeMap;
use alloc::string::String;

use cl_std::contract_api;
use cl_std::contract_api::pointers::ContractPointer;
use cl_std::contract_api::{add_uref, get_arg, new_uref};
use cl_std::key::Key;
use cl_std::uref::URef;
use cl_std::value::U512;

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
    let flag: String = get_arg(0);
    let do_nothing: ContractPointer = contract_api::store_function("do_nothing", BTreeMap::new());
    let do_something: ContractPointer =
        contract_api::store_function("do_something", BTreeMap::new());
    if flag == "pass1" {
        // Two calls should forward the internal RNG. This pass is a baseline.
        let uref1: URef = new_uref(U512::from(0)).into();
        let uref2: URef = new_uref(U512::from(1)).into();
        add_uref("uref1", &Key::URef(uref1));
        add_uref("uref2", &Key::URef(uref2));
    } else if flag == "pass2" {
        let uref1: URef = new_uref(U512::from(0)).into();
        add_uref("uref1", &Key::URef(uref1));
        // do_nothing doesn't do anything. It SHOULD not forward the internal RNG.
        let result: String = contract_api::call_contract(do_nothing.clone(), &(), &vec![]);
        assert_eq!(result, "Hello, world!");
        let uref2: URef = new_uref(U512::from(1)).into();
        add_uref("uref2", &Key::URef(uref2));
    } else if flag == "pass3" {
        let uref1: URef = new_uref(U512::from(0)).into();
        add_uref("uref1", &Key::URef(uref1));
        // do_something returns a new uref, and it should forward the internal RNG.
        let uref2: URef = contract_api::call_contract(do_something.clone(), &(), &vec![]);
        add_uref("uref2", &Key::URef(uref2));
    }
}
