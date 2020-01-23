#![no_std]

extern crate alloc;

use alloc::string::String;

use contract::{
    contract_api::{runtime, storage, system},
    unwrap_or_revert::UnwrapOrRevert,
};
use types::{ApiError, CLValue, URef};

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
    UnknownMethodName = 6,
}

impl From<CustomError> for ApiError {
    fn from(error: CustomError) -> Self {
        ApiError::User(error as u16)
    }
}

fn purse_name() -> String {
    runtime::get_arg(ApplyArgs::PurseName as u32)
        .unwrap_or_revert_with(CustomError::MissingPurseNameArg)
        .unwrap_or_revert_with(CustomError::InvalidPurseNameArg)
}

#[no_mangle]
pub extern "C" fn apply_method() {
    let method_name: String = runtime::get_arg(ApplyArgs::MethodName as u32)
        .unwrap_or_revert_with(CustomError::MissingMethodNameArg)
        .unwrap_or_revert_with(CustomError::InvalidMethodNameArg);
    match method_name.as_str() {
        METHOD_ADD => {
            let purse_name = purse_name();
            let purse_id = system::create_purse();
            runtime::put_key(&purse_name, purse_id.value().into());
        }
        METHOD_REMOVE => {
            let purse_name = purse_name();
            runtime::remove_key(&purse_name);
        }
        METHOD_VERSION => runtime::ret(CLValue::from_t(VERSION).unwrap_or_revert()),
        _ => runtime::revert(CustomError::UnknownMethodName),
    }
}

#[no_mangle]
pub extern "C" fn call() {
    let uref: URef = runtime::get_arg(CallArgs::PurseHolderURef as u32)
        .unwrap_or_revert_with(CustomError::MissingPurseHolderURefArg)
        .unwrap_or_revert_with(CustomError::InvalidPurseHolderURefArg);

    // this should overwrite the previous contract obj with the new contract obj at the same uref
    runtime::upgrade_contract_at_uref(ENTRY_FUNCTION_NAME, uref);

    // set new version
    let version_key = storage::new_turef(VERSION).into();
    runtime::put_key(METHOD_VERSION, version_key);
}
