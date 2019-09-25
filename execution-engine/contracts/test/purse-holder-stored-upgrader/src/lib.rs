#![no_std]

#[macro_use]
extern crate alloc;
extern crate contract_ffi;

use alloc::string::{String, ToString};
use contract_ffi::contract_api;
use contract_ffi::contract_api::Error;
use contract_ffi::uref::URef;

const ENTRY_FUNCTION_NAME: &str = "apply_method";
pub const METHOD_ADD: &str = "add";
pub const METHOD_REMOVE: &str = "remove";
pub const METHOD_VERSION: &str = "version";
pub const VERSION: &str = "1.0.1";

#[repr(u32)]
enum ApplyArgs {
    MethodName = 0,
    PurseName = 1,
}

#[repr(u32)]
enum CallArgs {
    PurseHolderURef = 0,
}

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

fn purse_name() -> String {
    match contract_api::get_arg(ApplyArgs::PurseName as u32) {
        Some(Ok(data)) => data,
        Some(Err(_)) => {
            contract_api::revert(Error::User(CustomError::InvalidPurseNameArg as u16).into())
        }
        None => contract_api::revert(Error::User(CustomError::MissingPurseNameArg as u16).into()),
    }
}

#[no_mangle]
pub extern "C" fn apply_method() {
    let method_name: String = match contract_api::get_arg(ApplyArgs::MethodName as u32) {
        Some(Ok(data)) => data,
        Some(Err(_)) => {
            contract_api::revert(Error::User(CustomError::InvalidMethodNameArg as u16).into())
        }
        None => contract_api::revert(Error::User(CustomError::MissingMethodNameArg as u16).into()),
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
        _ => contract_api::revert(Error::User(CustomError::UnknownMethodName as u16).into()),
    }
}

#[no_mangle]
pub extern "C" fn call() {
    let uref: URef = match contract_api::get_arg(CallArgs::PurseHolderURef as u32) {
        Some(Ok(data)) => data,
        Some(Err(_)) => {
            contract_api::revert(Error::User(CustomError::InvalidPurseHolderURefArg as u16).into())
        }
        None => {
            contract_api::revert(Error::User(CustomError::MissingPurseHolderURefArg as u16).into())
        }
    };

    let turef = contract_api::pointers::TURef::from_uref(uref).unwrap_or_else(|_| {
        contract_api::revert(Error::User(CustomError::InvalidTURef as u16).into())
    });

    // this should overwrite the previous contract obj with the new contract obj at the same uref
    contract_api::upgrade_contract_at_uref(ENTRY_FUNCTION_NAME, turef);

    // set new version
    let version_key = contract_api::new_turef(VERSION.to_string()).into();
    contract_api::add_uref(METHOD_VERSION, &version_key);
}
