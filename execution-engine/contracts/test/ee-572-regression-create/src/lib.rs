#![no_std]
#![feature(cell_update)]

#[macro_use]
extern crate alloc;
extern crate contract_ffi;

use alloc::string::{String, ToString};
use core::clone::Clone;
use core::convert::Into;

use contract_ffi::contract_api;
use contract_ffi::contract_api::pointers::UPointer;
use contract_ffi::key::Key;
use contract_ffi::uref::{AccessRights, URef};

const DATA: &str = "data";
const CONTRACT_NAME: &str = "create";

#[no_mangle]
pub extern "C" fn create() {
    let reference: UPointer<String> = contract_api::new_uref(DATA.to_string());

    let read_only_reference: URef = {
        let mut ret: UPointer<String> = reference.clone();
        ret.1 = AccessRights::READ;
        ret.into()
    };

    let extra_urefs = vec![read_only_reference];

    contract_api::ret(&read_only_reference, &extra_urefs)
}

#[no_mangle]
pub extern "C" fn call() {
    let contract: Key = contract_api::store_function(CONTRACT_NAME, Default::default()).into();
    contract_api::add_uref(CONTRACT_NAME, &contract)
}
