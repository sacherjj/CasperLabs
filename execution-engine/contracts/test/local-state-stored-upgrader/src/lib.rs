#![no_std]

extern crate contract_ffi;
extern crate local_state_stored_upgraded;

use contract_ffi::contract_api::{self, Error};
use contract_ffi::unwrap_or_revert::UnwrapOrRevert;
use contract_ffi::uref::URef;

#[repr(u16)]
enum Args {
    LocalStateURef = 0,
}

#[repr(u16)]
enum CustomError {
    MissingLocalStateURefArg = 0,
    InvalidLocalStateURefArg = 1,
    InvalidTURef = 2,
}

const ENTRY_FUNCTION_NAME: &str = "upgraded_delegate";

#[no_mangle]
pub extern "C" fn upgraded_delegate() {
    local_state_stored_upgraded::delegate()
}

#[no_mangle]
pub extern "C" fn call() {
    let uref: URef = contract_api::get_arg(Args::LocalStateURef as u32)
        .unwrap_or_revert_with(Error::User(CustomError::MissingLocalStateURefArg as u16))
        .unwrap_or_revert_with(Error::User(CustomError::InvalidLocalStateURefArg as u16));

    let turef = contract_api::pointers::TURef::from_uref(uref)
        .unwrap_or_else(|_| contract_api::revert(Error::User(CustomError::InvalidTURef as u16)));

    // this should overwrite the previous contract obj with the new contract obj at the same uref
    contract_api::upgrade_contract_at_uref(ENTRY_FUNCTION_NAME, turef);
}
