#![no_std]

#[macro_use]
extern crate alloc;
extern crate contract_ffi;

use contract_ffi::contract_api::{self, Error, PurseTransferResult};
use contract_ffi::value::account::PurseId;
use contract_ffi::value::U512;

const GET_PAYMENT_PURSE: &str = "get_payment_purse";

enum Arg {
    Amount = 0,
}

#[no_mangle]
pub extern "C" fn call() {
    let amount: U512 = contract_api::get_arg(Arg::Amount as u32);
    let main_purse: PurseId = contract_api::main_purse();

    let pos_pointer = contract_api::get_pos();

    let payment_purse: PurseId =
        contract_api::call_contract(pos_pointer, &(GET_PAYMENT_PURSE,), &vec![]);

    if let PurseTransferResult::TransferError =
        contract_api::transfer_from_purse_to_purse(main_purse, payment_purse, amount)
    {
        contract_api::revert(Error::Transfer.into());
    }
}
