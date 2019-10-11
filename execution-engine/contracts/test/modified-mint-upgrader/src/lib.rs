#![no_std]

extern crate contract_ffi;
extern crate modified_mint;

use contract_ffi::contract_api::{runtime, system, ContractRef, Error};

#[repr(u16)]
enum CustomError {
    ContractPointerHash = 1,
}

pub const EXT_FUNCTION_NAME: &str = "modified_mint_ext";

#[no_mangle]
pub extern "C" fn modified_mint_ext() {
    modified_mint::delegate();
}

#[no_mangle]
pub extern "C" fn call() {
    let mint_pointer = system::get_mint();

    let mint_turef = match mint_pointer {
        ContractRef::Hash(_) => {
            runtime::revert(Error::User(CustomError::ContractPointerHash as u16))
        }
        ContractRef::TURef(turef) => turef,
    };

    runtime::upgrade_contract_at_uref(EXT_FUNCTION_NAME, mint_turef);
}
