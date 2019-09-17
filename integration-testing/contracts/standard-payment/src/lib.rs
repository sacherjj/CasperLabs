#![no_std]

#[macro_use]
extern crate alloc;
extern crate contract_ffi;
extern crate contracts_common;

use contract_ffi::contract_api::{self, PurseTransferResult};
use contract_ffi::value::account::PurseId;
use contract_ffi::value::U512;
use contracts_common::Error;

const GET_PAYMENT_PURSE: &str = "get_payment_purse";

enum Arg {
    Amount = 0,
}

#[no_mangle]
pub extern "C" fn call() {
    let amount: U512 = contract_api::get_arg(Arg::Amount as u32);
    let main_purse: PurseId = contract_api::main_purse();

    let pos_pointer = contracts_common::get_pos_contract_read_only();

    let payment_purse: PurseId =
        contract_api::call_contract(pos_pointer, &(GET_PAYMENT_PURSE,), &vec![]);

    if let PurseTransferResult::TransferError =
        contract_api::transfer_from_purse_to_purse(main_purse, payment_purse, amount)
    {
        contract_api::revert(Error::Transfer as u32);
    }
}
