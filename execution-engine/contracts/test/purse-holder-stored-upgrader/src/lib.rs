#![no_std]

#[macro_use]
extern crate alloc;
extern crate contract_ffi;

use alloc::string::{String, ToString};
use contract_ffi::contract_api::{self, Error};
use contract_ffi::uref::URef;

const ENTRY_FUNCTION_NAME: &str = "apply_method";
pub const METHOD_ADD: &str = "add";
pub const METHOD_REMOVE: &str = "remove";
pub const METHOD_VERSION: &str = "version";
pub const VERSION: &str = "1.0.1";

#[repr(u16)]
enum ApplyArgs {
    MethodName = 0,
    PurseName = 1,
}

#[repr(u16)]
enum CallArgs {
    PurseHolderURef = 0,
}

#[repr(u16)]
enum CustomError {
    MissingPurseHolderURefArg = 0,
    InvalidPurseHolderURefArg = 1,
    MissingMethodNameArg = 2,
    InvalidMethodNameArg = 3,
    MissingPurseNameArg = 4,
    InvalidPurseNameArg = 5,
    InvalidTURef = 6,
    UnknownMethodName = 7,
}

impl From<CustomError> for Error {
    fn from(error: CustomError) -> Self {
        Error::User(error as u16)
    }
}

fn purse_name() -> String {
    match contract_api::get_arg(ApplyArgs::PurseName as u32) {
        Some(Ok(data)) => data,
        Some(Err(_)) => contract_api::revert_with_error(CustomError::InvalidPurseNameArg),
        None => contract_api::revert_with_error(CustomError::MissingPurseNameArg),
    }
}

#[no_mangle]
pub extern "C" fn apply_method() {
    let method_name: String = match contract_api::get_arg(ApplyArgs::MethodName as u32) {
        Some(Ok(data)) => data,
        Some(Err(_)) => contract_api::revert_with_error(CustomError::InvalidMethodNameArg),
        None => contract_api::revert_with_error(CustomError::MissingMethodNameArg),
    };
    match method_name.as_str() {
        METHOD_ADD => {
            let purse_name = purse_name();
            let purse_id = contract_api::create_purse();
            contract_api::add_uref(&purse_name, &purse_id.value().into());
        }
        METHOD_REMOVE => {
            let purse_name = purse_name();
            contract_api::remove_uref(&purse_name);
        }
        METHOD_VERSION => contract_api::ret(&VERSION.to_string(), &vec![]),
        _ => contract_api::revert_with_error(CustomError::UnknownMethodName),
    }
}

#[no_mangle]
pub extern "C" fn call() {
    let uref: URef = match contract_api::get_arg(CallArgs::PurseHolderURef as u32) {
        Some(Ok(data)) => data,
        Some(Err(_)) => contract_api::revert_with_error(CustomError::InvalidPurseHolderURefArg),
        None => contract_api::revert_with_error(CustomError::MissingPurseHolderURefArg),
    };

    let turef = contract_api::pointers::TURef::from_uref(uref)
        .unwrap_or_else(|_| contract_api::revert_with_error(CustomError::InvalidTURef));

    // this should overwrite the previous contract obj with the new contract obj at the same uref
    contract_api::upgrade_contract_at_uref(ENTRY_FUNCTION_NAME, turef);

    // set new version
    let version_key = contract_api::new_turef(VERSION.to_string()).into();
    contract_api::add_uref(METHOD_VERSION, &version_key);
}
