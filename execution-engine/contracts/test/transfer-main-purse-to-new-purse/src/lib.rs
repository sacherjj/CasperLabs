#![no_std]

extern crate alloc;

extern crate contract_ffi;

use alloc::string::String;

use contract_ffi::contract_api::{self, Error};
use contract_ffi::unwrap_or_revert::UnwrapOrRevert;
use contract_ffi::value::account::PurseId;
use contract_ffi::value::U512;

#[no_mangle]
pub extern "C" fn call() {
    let amount: U512 = contract_api::runtime::get_arg(1)
        .unwrap_or_revert_with(Error::MissingArgument)
        .unwrap_or_revert_with(Error::InvalidArgument);

    let destination_name: String = contract_api::runtime::get_arg(0)
        .unwrap_or_revert_with(Error::MissingArgument)
        .unwrap_or_revert_with(Error::InvalidArgument);

    let source: PurseId = contract_api::account::get_main_purse();
    let destination = contract_api::system::create_purse();
    contract_api::system::transfer_from_purse_to_purse(source, destination, amount)
        .unwrap_or_revert();
    contract_api::runtime::put_key(&destination_name, &destination.value().into());
}
