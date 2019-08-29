#![no_std]
#![feature(cell_update)]

extern crate alloc;
extern crate contract_ffi;

use alloc::string::{String, ToString};
use alloc::vec::Vec;

use contract_ffi::contract_api;
use contract_ffi::contract_api::pointers::{ContractPointer, UPointer};
use contract_ffi::key::Key;
use contract_ffi::uref::{AccessRights, URef};

const CONTRACT_POINTER: u32 = 0;

const GET_ARG_ERROR: u32 = 100;
const CREATE_UPOINTER_ERROR: u32 = 200;

const REPLACEMENT_DATA: &str = "bawitdaba";

#[no_mangle]
pub extern "C" fn call() {
    let contract_pointer: ContractPointer = contract_api::get_arg::<Key>(CONTRACT_POINTER)
        .to_c_ptr()
        .unwrap_or_else(|| contract_api::revert(GET_ARG_ERROR));

    let reference: URef = contract_api::call_contract(contract_pointer, &(), &Vec::new());

    let forged_reference: UPointer<String> = {
        let ret = URef::new(reference.addr(), AccessRights::READ_ADD_WRITE);
        UPointer::from_uref(ret).unwrap_or_else(|_| contract_api::revert(CREATE_UPOINTER_ERROR))
    };

    contract_api::write(forged_reference, REPLACEMENT_DATA.to_string())
}
