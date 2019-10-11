#![no_std]

#[macro_use]
extern crate alloc;
extern crate contract_ffi;

#[cfg(not(feature = "lib"))]
use alloc::collections::BTreeMap;
use alloc::string::{String, ToString};

use contract_ffi::contract_api::ContractRef;
use contract_ffi::contract_api::{runtime, storage, system, Error};
use contract_ffi::key::Key;
use contract_ffi::unwrap_or_revert::UnwrapOrRevert;

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
    runtime::get_arg(Args::PurseName as u32)
        .unwrap_or_revert_with(Error::User(CustomError::MissingPurseNameArg as u16))
        .unwrap_or_revert_with(Error::User(CustomError::InvalidPurseNameArg as u16))
}

#[no_mangle]
pub extern "C" fn apply_method() {
    let method_name: String = runtime::get_arg(Args::MethodName as u32)
        .unwrap_or_revert_with(Error::User(CustomError::MissingMethodNameArg as u16))
        .unwrap_or_revert_with(Error::User(CustomError::InvalidMethodNameArg as u16));
    match method_name.as_str() {
        METHOD_ADD => {
            let purse_name = purse_name();
            let purse_id = system::create_purse();
            runtime::put_key(&purse_name, &purse_id.value().into());
        }
        METHOD_VERSION => runtime::ret(VERSION.to_string(), vec![]),
        _ => runtime::revert(Error::User(CustomError::UnknownMethodName as u16)),
    }
}

#[cfg(not(feature = "lib"))]
#[no_mangle]
pub extern "C" fn call() {
    let mint_uref = match system::get_mint() {
        ContractRef::Hash(_) => runtime::revert(Error::User(CustomError::MintHash as u16)),
        ContractRef::TURef(turef) => turef.into(),
    };

    let named_keys = {
        let mut tmp = BTreeMap::new();
        tmp.insert(String::from(MINT_NAME), Key::URef(mint_uref));
        tmp
    };

    let key = storage::store_function(ENTRY_FUNCTION_NAME, named_keys)
        .into_turef()
        .unwrap_or_revert_with(Error::UnexpectedContractRefVariant)
        .into();

    runtime::put_key(CONTRACT_NAME, &key);

    // set version
    let version_key = storage::new_turef(VERSION.to_string()).into();
    runtime::put_key(METHOD_VERSION, &version_key);
}
