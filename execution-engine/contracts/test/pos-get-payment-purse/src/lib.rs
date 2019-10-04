#![no_std]

extern crate alloc;
extern crate contract_ffi;
use alloc::vec::Vec;

use contract_ffi::contract_api::{self, Error as ApiError};
use contract_ffi::value::account::PurseId;
use contract_ffi::value::uint::U512;

#[repr(u16)]
enum Error {
    TransferFromSourceToPayment = 0,
    TransferFromPaymentToSource,
    GetBalance,
    CheckBalance,
}

#[no_mangle]
pub extern "C" fn call() {
    let pos_pointer = contract_api::get_pos();
    let source_purse = contract_api::main_purse();
    let payment_amount: U512 = 100.into();
    // amount passed to payment contract
    let payment_fund: U512 = match contract_api::get_arg(0) {
        Some(Ok(data)) => data,
        Some(Err(_)) => contract_api::revert(ApiError::InvalidArgument.into()),
        None => contract_api::revert(ApiError::MissingArgument.into()),
    };
    let payment_purse: PurseId =
        contract_api::call_contract(pos_pointer, &("get_payment_purse",), &Vec::new());

    // can deposit
    if contract_api::transfer_from_purse_to_purse(source_purse, payment_purse, payment_amount)
        .is_err()
    {
        contract_api::revert(ApiError::User(Error::TransferFromSourceToPayment as u16).into());
    }

    let payment_balance = match contract_api::get_balance(payment_purse) {
        Some(amount) => amount,
        None => contract_api::revert(ApiError::User(Error::GetBalance as u16).into()),
    };

    if payment_balance.saturating_sub(payment_fund) != payment_amount {
        contract_api::revert(ApiError::User(Error::CheckBalance as u16).into())
    }

    // cannot withdraw
    if contract_api::transfer_from_purse_to_purse(payment_purse, source_purse, payment_amount)
        .is_ok()
    {
        contract_api::revert(ApiError::User(Error::TransferFromPaymentToSource as u16).into());
    }
}
