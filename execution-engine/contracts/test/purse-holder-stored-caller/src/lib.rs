#![no_std]

#[macro_use]
extern crate alloc;
extern crate contract_ffi;

use alloc::string::String;
use contract_ffi::contract_api::pointers::{ContractPointer, TURef};
use contract_ffi::contract_api::{self, Error};
use contract_ffi::uref::URef;

pub const METHOD_VERSION: &str = "version";

#[repr(u32)]
enum Args {
    PurseHolderURef = 0,
    MethodName = 1,
    PurseName = 2,
}

#[allow(clippy::enum_variant_names)]
#[repr(u16)]
enum CustomError {
    MissingPurseHolderURefArg = 0,
    InvalidPurseHolderURefArg = 1,
    MissingMethodNameArg = 2,
    InvalidMethodNameArg = 3,
    MissingPurseNameArg = 4,
    InvalidPurseNameArg = 5,
}

#[no_mangle]
pub extern "C" fn call() {
    let purse_holder_uref: URef = match contract_api::get_arg(Args::PurseHolderURef as u32) {
        Some(Ok(uref)) => uref,
        Some(Err(_)) => {
            contract_api::revert(Error::User(CustomError::InvalidPurseHolderURefArg as u16).into())
        }
        None => {
            contract_api::revert(Error::User(CustomError::MissingPurseHolderURefArg as u16).into())
        }
    };
    let method_name: String = match contract_api::get_arg(Args::MethodName as u32) {
        Some(Ok(method)) => method,
        Some(Err(_)) => {
            contract_api::revert(Error::User(CustomError::InvalidMethodNameArg as u16).into())
        }
        None => contract_api::revert(Error::User(CustomError::MissingMethodNameArg as u16).into()),
    };

    let purse_holder_contract_pointer = ContractPointer::URef(TURef::new(
        purse_holder_uref.addr(),
        contract_ffi::uref::AccessRights::READ,
    ));

    match method_name.as_str() {
        METHOD_VERSION => {
            let version: String = contract_api::call_contract(
                purse_holder_contract_pointer.clone(),
                &(method_name,),
                &vec![],
            );
            let version_key = contract_api::new_turef(version).into();
            contract_api::add_uref(METHOD_VERSION, &version_key);
        }
        _ => {
            let purse_name: String = match contract_api::get_arg(Args::PurseName as u32) {
                Some(Ok(purse)) => purse,
                Some(Err(_)) => contract_api::revert(
                    Error::User(CustomError::InvalidPurseNameArg as u16).into(),
                ),
                None => contract_api::revert(
                    Error::User(CustomError::MissingPurseNameArg as u16).into(),
                ),
            };

            contract_api::call_contract::<_, ()>(
                purse_holder_contract_pointer.clone(),
                &(method_name, purse_name),
                &vec![],
            );
        }
    };
}
