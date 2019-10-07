#![no_std]
#![feature(cell_update)]

extern crate alloc;
extern crate contract_ffi;

use alloc::string::{String, ToString};
use alloc::vec::Vec;

use contract_ffi::contract_api::pointers::{ContractPointer, TURef};
use contract_ffi::contract_api::{self, Error as ApiError};
use contract_ffi::key::Key;
use contract_ffi::uref::{AccessRights, URef};

const CONTRACT_POINTER: u32 = 0;

#[repr(u16)]
enum Error {
    GetArgument = 0,
    CreateTURef,
}

const REPLACEMENT_DATA: &str = "bawitdaba";

#[no_mangle]
pub extern "C" fn call() {
    let contract_pointer: ContractPointer = match contract_api::get_arg::<Key>(CONTRACT_POINTER) {
        Some(Ok(data)) => data
            .to_c_ptr()
            .unwrap_or_else(|| contract_api::revert(ApiError::User(Error::GetArgument as u16))),
        Some(Err(_)) => contract_api::revert(ApiError::InvalidArgument),
        None => contract_api::revert(ApiError::MissingArgument),
    };

    let reference: URef = contract_api::call_contract(contract_pointer, &(), &Vec::new());

    let forged_reference: TURef<String> = {
        let ret = URef::new(reference.addr(), AccessRights::READ_ADD_WRITE);
        TURef::from_uref(ret)
            .unwrap_or_else(|_| contract_api::revert(ApiError::User(Error::CreateTURef as u16)))
    };

    contract_api::write(forged_reference, REPLACEMENT_DATA.to_string())
}
