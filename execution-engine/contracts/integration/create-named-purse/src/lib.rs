#![no_std]

extern crate alloc;
extern crate contract_ffi;

use alloc::string::String;
use contract_ffi::contract_api::{self, PurseTransferResult};
use contract_ffi::key::Key;
use contract_ffi::value::account::PurseId;
use contract_ffi::value::U512;

enum Error {
    Transfer = 1,
}

enum Arg {
    Amount = 0,
    Name = 1,
}

#[no_mangle]
pub extern "C" fn call() {
    let amount: U512 = contract_api::get_arg(Arg::Amount as u32).unwrap().unwrap();
    let name: String = contract_api::get_arg(Arg::Name as u32).unwrap().unwrap();
    let main_purse: PurseId = contract_api::main_purse();
    let new_purse: PurseId = contract_api::create_purse();

    if let PurseTransferResult::TransferError =
        contract_api::transfer_from_purse_to_purse(main_purse, new_purse, amount)
    {
        contract_api::revert(Error::Transfer as u32);
    }

    let new_purse_key: Key = new_purse.value().into();
    contract_api::put_key(&name, &new_purse_key);
}
