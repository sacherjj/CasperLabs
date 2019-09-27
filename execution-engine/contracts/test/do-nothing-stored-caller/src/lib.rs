#![no_std]

#[macro_use]
extern crate alloc;
extern crate contract_ffi;

use alloc::string::String;
use contract_ffi::contract_api::pointers::{ContractPointer, TURef};
use contract_ffi::contract_api::{self, Error};
use contract_ffi::uref::URef;

#[repr(u32)]
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

    let do_nothing_uref: URef = match contract_api::get_arg(Args::DoNothingURef as u32) {
        Some(Ok(data)) => data,
        Some(Err(_)) => contract_api::revert(Error::InvalidArgument.into()),
        None => {
            contract_api::revert(Error::User(CustomError::MissingDoNothingURefArg as u16).into())
        }
    };

    let do_nothing_contract_pointer = ContractPointer::URef(TURef::new(
        do_nothing_uref.addr(),
        contract_ffi::uref::AccessRights::READ,
    ));

    // call do_nothing_stored
    contract_api::call_contract::<_, ()>(
        do_nothing_contract_pointer.clone(),
        &(new_purse_name.clone(),),
        &vec![],
    );
}
