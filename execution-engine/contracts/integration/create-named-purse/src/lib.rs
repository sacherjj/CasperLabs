#![no_std]

extern crate alloc;

extern crate contract_ffi;

use alloc::string::String;

use contract_ffi::contract_api::{self, Error};
use contract_ffi::key::Key;
use contract_ffi::unwrap_or_revert::UnwrapOrRevert;
use contract_ffi::value::account::PurseId;
use contract_ffi::value::U512;

enum Arg {
    Amount = 0,
    Name = 1,
}

#[no_mangle]
pub extern "C" fn call() {
    let amount: U512 = contract_api::get_arg(Arg::Amount as u32)
        .unwrap_or_revert_with(Error::MissingArgument)
        .unwrap_or_revert_with(Error::InvalidArgument);
    let name: String = contract_api::get_arg(Arg::Name as u32)
        .unwrap_or_revert_with(Error::MissingArgument)
        .unwrap_or_revert_with(Error::InvalidArgument);
    let main_purse: PurseId = contract_api::main_purse();
    let new_purse: PurseId = contract_api::create_purse();

    contract_api::transfer_from_purse_to_purse(main_purse, new_purse, amount).unwrap_or_revert();

    let new_purse_key: Key = new_purse.value().into();
    contract_api::put_key(&name, &new_purse_key);
}
