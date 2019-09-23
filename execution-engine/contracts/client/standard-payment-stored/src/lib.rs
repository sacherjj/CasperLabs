#![no_std]

#[macro_use]
extern crate alloc;
extern crate contract_ffi;

use alloc::collections::BTreeMap;
use alloc::string::String;

use contract_ffi::contract_api::{self, PurseTransferResult};
use contract_ffi::key::Key;
use contract_ffi::value::account::PurseId;
use contract_ffi::value::U512;

const GET_PAYMENT_PURSE: &str = "get_payment_purse";
const STANDARD_PAYMENT_CONTRACT_NAME: &str = "standard_payment";
const PAY_FUNCTION_NAME: &str = "pay";

enum Arg {
    Amount = 0,
}

enum Error {
    GetPosURef = 1,
    Transfer = 2,
    MissingArgument = 100,
    InvalidArgument = 101,
}

#[no_mangle]
pub extern "C" fn pay() {
    let amount: U512 = match contract_api::get_arg(Arg::Amount as u32) {
        Some(Ok(data)) => data,
        Some(Err(_)) => contract_api::revert(Error::InvalidArgument as u32),
        None => contract_api::revert(Error::MissingArgument as u32),
    };
    let main_purse: PurseId = contract_api::main_purse();

    let pos_pointer =
        contract_api::get_pos().unwrap_or_else(|| contract_api::revert(Error::GetPosURef as u32));

    let payment_purse: PurseId =
        contract_api::call_contract(pos_pointer, &(GET_PAYMENT_PURSE,), &vec![]);

    if let PurseTransferResult::TransferError =
        contract_api::transfer_from_purse_to_purse(main_purse, payment_purse, amount)
    {
        contract_api::revert(Error::Transfer as u32);
    }
}

#[no_mangle]
pub extern "C" fn call() {
    let known_urefs: BTreeMap<String, Key> = BTreeMap::new();
    let pointer = contract_api::store_function(PAY_FUNCTION_NAME, known_urefs);
    contract_api::add_uref(STANDARD_PAYMENT_CONTRACT_NAME, &pointer.into());
}
