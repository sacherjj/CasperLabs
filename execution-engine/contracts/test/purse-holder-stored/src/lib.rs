#![no_std]
#![feature(cell_update)]

#[macro_use]
extern crate alloc;
extern crate contract_ffi;

use alloc::collections::BTreeMap;
use alloc::string::{String, ToString};
use contract_ffi::contract_api::pointers::ContractPointer;
use contract_ffi::contract_api::{self, Error};
use contract_ffi::key::Key;

const MINT_NAME: &str = "mint";
const ENTRY_FUNCTION_NAME: &str = "apply_method";
const CONTRACT_NAME: &str = "purse_holder_stored";
pub const METHOD_ADD: &str = "add";
pub const METHOD_VERSION: &str = "version";
pub const VERSION: &str = "1.0.0";

#[repr(u16)]
enum Args {
    MethodName = 0,
    PurseName = 1,
}

#[repr(u16)]
enum CustomError {
    MintHash = 0,
    MissingMethodNameArg = 1,
    InvalidMethodNameArg = 2,
    MissingPurseNameArg = 3,
    InvalidPurseNameArg = 4,
    UnknownMethodName = 5,
}

fn purse_name() -> String {
    match contract_api::get_arg(Args::PurseName as u32) {
        Some(Ok(data)) => data,
        Some(Err(_)) => {
            contract_api::revert(Error::User(CustomError::InvalidPurseNameArg as u16).into())
        }
        None => contract_api::revert(Error::User(CustomError::MissingPurseNameArg as u16).into()),
    }
}

#[no_mangle]
pub extern "C" fn apply_method() {
    let method_name: String = match contract_api::get_arg(Args::MethodName as u32) {
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
        METHOD_VERSION => contract_api::ret(&VERSION.to_string(), &vec![]),
        _ => contract_api::revert(Error::User(CustomError::UnknownMethodName as u16).into()),
    }
}

#[cfg(not(feature = "lib"))]
#[no_mangle]
pub extern "C" fn call() {
    let mint_uref = match contract_api::get_mint() {
        ContractPointer::Hash(_) => {
            contract_api::revert(Error::User(CustomError::MintHash as u16).into())
        }
        ContractPointer::URef(turef) => turef.into(),
    };
    let mint_key = Key::URef(mint_uref);
    let mut known_urefs: BTreeMap<String, Key> = BTreeMap::new();
    known_urefs.insert(String::from(MINT_NAME), mint_key);
    let contract = contract_api::fn_by_name(ENTRY_FUNCTION_NAME, known_urefs);
    let contract_name_key = contract_api::new_turef(contract).into();
    contract_api::add_uref(CONTRACT_NAME, &contract_name_key);

    // set version
    let version_key = contract_api::new_turef(VERSION.to_string()).into();
    contract_api::add_uref(METHOD_VERSION, &version_key);
}
