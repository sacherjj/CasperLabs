#![no_std]

extern crate alloc;

use alloc::string::String;

use contract::{
    contract_api::{runtime, storage},
    unwrap_or_revert::UnwrapOrRevert,
};
use types::{AccessRights, ApiError, ContractRef, URef};

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
    let purse_holder_uref: URef = runtime::get_arg(Args::PurseHolderURef as u32)
        .unwrap_or_revert_with(ApiError::User(
            CustomError::MissingPurseHolderURefArg as u16,
        ))
        .unwrap_or_revert_with(ApiError::User(
            CustomError::InvalidPurseHolderURefArg as u16,
        ));
    let method_name: String = runtime::get_arg(Args::MethodName as u32)
        .unwrap_or_revert_with(ApiError::User(CustomError::MissingMethodNameArg as u16))
        .unwrap_or_revert_with(ApiError::User(CustomError::InvalidMethodNameArg as u16));

    let purse_holder_contract_pointer =
        ContractRef::URef(URef::new(purse_holder_uref.addr(), AccessRights::READ));

    match method_name.as_str() {
        METHOD_VERSION => {
            let version: String =
                runtime::call_contract(purse_holder_contract_pointer, (method_name,));
            let version_key = storage::new_turef(version).into();
            runtime::put_key(METHOD_VERSION, version_key);
        }
        _ => {
            let purse_name: String = runtime::get_arg(Args::PurseName as u32)
                .unwrap_or_revert_with(ApiError::User(CustomError::MissingPurseNameArg as u16))
                .unwrap_or_revert_with(ApiError::User(CustomError::InvalidPurseNameArg as u16));

            runtime::call_contract::<_, ()>(
                purse_holder_contract_pointer,
                (method_name, purse_name),
            );
        }
    };
}
