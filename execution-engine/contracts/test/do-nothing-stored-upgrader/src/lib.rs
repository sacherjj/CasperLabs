#![no_std]

extern crate contract_ffi;
extern crate create_purse_01;

use contract_ffi::contract_api::pointers::TURef;
use contract_ffi::contract_api::{self, Error};

const ENTRY_FUNCTION_NAME: &str = "delegate";

#[repr(u16)]
enum Args {
    DoNothingURef = 0,
}

#[repr(u16)]
enum CustomError {
    MissingDoNothingURefArg = 0,
    InvalidDoNothingURefArg = 1,
    InvalidTURef = 2,
}

#[no_mangle]
pub extern "C" fn delegate() {
    create_purse_01::delegate()
}

#[no_mangle]
pub extern "C" fn call() {
    let turef = match contract_api::get_arg(Args::DoNothingURef as u32) {
        Some(Ok(data)) => TURef::from_uref(data).unwrap_or_else(|_| {
            contract_api::revert(Error::User(CustomError::InvalidTURef as u16).into())
        }),
        Some(Err(_)) => {
            contract_api::revert(Error::User(CustomError::InvalidDoNothingURefArg as u16).into())
        }
        None => {
            contract_api::revert(Error::User(CustomError::MissingDoNothingURefArg as u16).into())
        }
    };

    // this should overwrite the previous contract obj with the new contract obj at the same uref
    contract_api::upgrade_contract_at_uref(ENTRY_FUNCTION_NAME, turef);
}
