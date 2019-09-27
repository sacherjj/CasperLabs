#![no_std]

#[macro_use]
extern crate alloc;
extern crate contract_ffi;

use alloc::string::String;
use contract_ffi::contract_api::pointers::{ContractPointer, TURef};
use contract_ffi::contract_api::{self, Error};
use contract_ffi::uref::{AccessRights, URef};

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
    let new_purse_name: String = match contract_api::get_arg(Args::PurseName as u32) {
        Some(Ok(name)) => name,
        Some(Err(_)) => contract_api::revert(Error::InvalidArgument.into()),
        None => contract_api::revert(Error::User(CustomError::MissingPurseNameArg as u16).into()),
    };

    let do_nothing: ContractPointer =
        match contract_api::get_arg::<URef>(Args::DoNothingURef as u32) {
            Some(Ok(uref)) => ContractPointer::URef(TURef::new(uref.addr(), AccessRights::READ)),
            Some(Err(_)) => contract_api::revert(Error::InvalidArgument.into()),
            None => contract_api::revert(
                Error::User(CustomError::MissingDoNothingURefArg as u16).into(),
            ),
        };

    contract_api::call_contract::<_, ()>(do_nothing.clone(), &(new_purse_name.clone(),), &vec![]);
}
