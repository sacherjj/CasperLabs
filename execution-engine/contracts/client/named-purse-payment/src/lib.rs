#![no_std]

#[macro_use]
extern crate alloc;
extern crate contract_ffi;

use alloc::string::String;
use contract_ffi::contract_api::{self, PurseTransferResult};
use contract_ffi::key::Key;
use contract_ffi::value::account::PurseId;
use contract_ffi::value::U512;

const GET_PAYMENT_PURSE: &str = "get_payment_purse";
const SET_REFUND_PURSE: &str = "set_refund_purse";

enum Arg {
    PurseName = 0,
    Amount = 1,
}

enum Error {
    GetPosURef = 1,
    Transfer = 2,
    InvalidPurseName = 3,
    InvalidPurse = 4,
    MissingArgument = 100,
    InvalidArgument = 101,
}

#[no_mangle]
pub extern "C" fn call() {
    let purse_name: String = match contract_api::get_arg(Arg::PurseName as u32) {
        Some(Ok(data)) => data,
        Some(Err(_)) => contract_api::revert(Error::InvalidArgument as u32),
        None => contract_api::revert(Error::MissingArgument as u32),
    };

    let purse_key = contract_api::get_uref(&purse_name)
        .unwrap_or_else(|| contract_api::revert(Error::InvalidPurseName as u32));
    let purse = match purse_key.as_uref() {
        Some(uref) => PurseId::new(*uref),
        None => contract_api::revert(Error::InvalidPurse as u32),
    };

    let amount: U512 = match contract_api::get_arg(Arg::Amount as u32) {
        Some(Ok(data)) => data,
        Some(Err(_)) => contract_api::revert(Error::InvalidArgument as u32),
        None => contract_api::revert(Error::MissingArgument as u32),
    };

    let pos_pointer =
        contract_api::get_pos().unwrap_or_else(|| contract_api::revert(Error::GetPosURef as u32));

    let payment_purse: PurseId =
        contract_api::call_contract(pos_pointer.clone(), &(GET_PAYMENT_PURSE,), &vec![]);

    contract_api::call_contract::<_, ()>(
        pos_pointer,
        &(SET_REFUND_PURSE, purse),
        &vec![Key::URef(purse.value())],
    );

    if let PurseTransferResult::TransferError =
        contract_api::transfer_from_purse_to_purse(purse, payment_purse, amount)
    {
        contract_api::revert(Error::Transfer as u32);
    }
}
