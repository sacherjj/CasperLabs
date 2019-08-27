#![no_std]
#![feature(alloc, cell_update)]

extern crate alloc;
extern crate cl_std;

use alloc::string::String;

use cl_std::contract_api;
use cl_std::contract_api::pointers::{ContractPointer, UPointer};
use cl_std::key::Key;
use cl_std::uref::URef;

enum Error {
    HelloExtNotFound = 1,
    InvalidURef = 2,
}

#[no_mangle]
pub extern "C" fn call() {
    let contract_key: Key = contract_api::get_uref("hello_ext")
        .unwrap_or_else(|| contract_api::revert(Error::HelloExtNotFound as u32));
    let contract_pointer: ContractPointer = match contract_key {
        Key::Hash(hash) => ContractPointer::Hash(hash),
        _ => contract_api::revert(Error::InvalidURef as u32),
    };

    let extra_urefs = [].to_vec();

    let result: URef = contract_api::call_contract(contract_pointer, &(), &extra_urefs);

    let value: String = contract_api::read(UPointer::from_uref(result).unwrap());

    assert_eq!("Hello, world!", &value);
}
