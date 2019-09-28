#![no_std]
#![feature(cell_update)]

extern crate alloc;
extern crate contract_ffi;

use alloc::string::String;
use contract_ffi::contract_api::{self, Error};
use contract_ffi::value::account::PurseId;
use contract_ffi::value::U512;

#[no_mangle]
pub extern "C" fn call() {
    let amount: U512 = match contract_api::get_arg(1) {
        Some(Ok(data)) => data,
        Some(Err(_)) => contract_api::revert(Error::InvalidArgument.into()),
        None => contract_api::revert(Error::MissingArgument.into()),
    };

    let destination_name: String = match contract_api::get_arg(0) {
        Some(Ok(data)) => data,
        Some(Err(_)) => contract_api::revert(Error::InvalidArgument.into()),
        None => contract_api::revert(Error::MissingArgument.into()),
    };

    let source: PurseId = contract_api::main_purse();
    let destination = contract_api::create_purse();
    if contract_api::transfer_from_purse_to_purse(source, destination, amount)
        == contract_api::PurseTransferResult::TransferError
    {
        contract_api::revert(Error::Transfer.into());
    }
    contract_api::add_uref(&destination_name, &destination.value().into());
}
