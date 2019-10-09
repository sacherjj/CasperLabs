#![no_std]

extern crate contract_ffi;
extern crate modified_mint;

use contract_ffi::contract_api;
use contract_ffi::contract_api::pointers::ContractPointer;
use contract_ffi::contract_api::Error;

#[repr(u16)]
enum CustomError {
    ContractPointerHash = 1,
}

pub const MINT_NAME: &str = "mint";
pub const EXT_FUNCTION_NAME: &str = "modified_mint_ext";

#[no_mangle]
pub extern "C" fn modified_mint_ext() {
    modified_mint::delegate();
}

#[no_mangle]
pub extern "C" fn call() {
    let mint_pointer = contract_api::system::get_mint();

    let mint_turef = match mint_pointer {
        ContractPointer::Hash(_) => {
            contract_api::runtime::revert(Error::User(CustomError::ContractPointerHash as u16))
        }
        ContractPointer::URef(turef) => turef,
    };

    contract_api::runtime::upgrade_contract_at_uref(EXT_FUNCTION_NAME, mint_turef);
}
