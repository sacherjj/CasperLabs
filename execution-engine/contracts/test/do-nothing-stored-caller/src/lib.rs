#![no_std]

extern crate alloc;

use alloc::string::String;

use contract::{contract_api::runtime, unwrap_or_revert::UnwrapOrRevert};
use types::{AccessRights, ApiError, ContractRef, URef};

#[repr(u16)]
enum Args {
    DoNothingURef = 0,
    PurseName = 1,
}

#[repr(u16)]
enum CustomError {
    MissingDoNothingURefArg = 0,
    MissingPurseNameArg = 1,
}

#[no_mangle]
pub extern "C" fn call() {
    let new_purse_name: String = runtime::get_arg(Args::PurseName as u32)
        .unwrap_or_revert_with(ApiError::User(CustomError::MissingPurseNameArg as u16))
        .unwrap_or_revert_with(ApiError::InvalidArgument);

    let arg: URef = runtime::get_arg(Args::DoNothingURef as u32)
        .unwrap_or_revert_with(ApiError::User(CustomError::MissingDoNothingURefArg as u16))
        .unwrap_or_revert_with(ApiError::InvalidArgument);
    let do_nothing = ContractRef::URef(URef::new(arg.addr(), AccessRights::READ));

    runtime::call_contract(do_nothing, (new_purse_name,))
}
