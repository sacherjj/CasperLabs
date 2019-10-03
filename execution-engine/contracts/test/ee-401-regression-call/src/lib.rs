#![no_std]
#![feature(cell_update)]

extern crate alloc;
extern crate contract_ffi;

use alloc::string::ToString;

use contract_ffi::contract_api::pointers::{ContractPointer, TURef};
use contract_ffi::contract_api::{self, Error};
use contract_ffi::key::Key;
use contract_ffi::uref::URef;

#[no_mangle]
pub extern "C" fn call() {
    let contract_key: Key = contract_api::get_key("hello_ext")
        .unwrap_or_else(|| contract_api::revert(Error::GetURef.into()));
    let contract_pointer: ContractPointer = match contract_key {
        Key::Hash(hash) => ContractPointer::Hash(hash),
        _ => contract_api::revert(Error::UnexpectedKeyVariant.into()),
    };

    let extra_urefs = [].to_vec();

    let result: URef = contract_api::call_contract(contract_pointer, &(), &extra_urefs);

    let value = contract_api::read(TURef::from_uref(result).unwrap());

    assert_eq!(Ok(Some("Hello, world!".to_string())), value);
}
