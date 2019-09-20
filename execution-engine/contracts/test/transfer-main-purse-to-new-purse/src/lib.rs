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
    let amount: U512 = contract_api::get_arg(1);
    let destination_name: String = contract_api::get_arg(0);
    let source: PurseId = contract_api::main_purse();
    let destination = contract_api::create_purse();
    if contract_api::transfer_from_purse_to_purse(source, destination, amount)
        == contract_api::PurseTransferResult::TransferError
    {
        contract_api::revert(Error::Transfer.into());
    }
    contract_api::add_uref(&destination_name, &destination.value().into());
}
