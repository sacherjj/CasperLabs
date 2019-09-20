#![no_std]

extern crate alloc;
extern crate contract_ffi;
use alloc::vec::Vec;

use contract_ffi::contract_api::{self, Error as ApiError, PurseTransferResult};
use contract_ffi::value::account::PurseId;
use contract_ffi::value::uint::U512;

enum Error {
    TransferFromSourceToPayment = ApiError::unreserved_min_plus(0),
    TransferFromPaymentToSource = ApiError::unreserved_min_plus(1),
    GetBalance = ApiError::unreserved_min_plus(2),
    CheckBalance = ApiError::unreserved_min_plus(3),
}

#[no_mangle]
pub extern "C" fn call() {
    let pos_pointer = contract_api::get_pos();
    let source_purse = contract_api::main_purse();
    let payment_amount: U512 = 100.into();
    // amount passed to payment contract
    let payment_fund: U512 = contract_api::get_arg(0);
    let payment_purse: PurseId =
        contract_api::call_contract(pos_pointer, &("get_payment_purse",), &Vec::new());

    // can deposit
    if let PurseTransferResult::TransferError =
        contract_api::transfer_from_purse_to_purse(source_purse, payment_purse, payment_amount)
    {
        contract_api::revert(Error::TransferFromSourceToPayment as u32);
    }

    let payment_balance = match contract_api::get_balance(payment_purse) {
        Some(amount) => amount,
        None => contract_api::revert(Error::GetBalance as u32),
    };

    if payment_balance.saturating_sub(payment_fund) != payment_amount {
        contract_api::revert(Error::CheckBalance as u32)
    }

    // cannot withdraw
    if let PurseTransferResult::TransferSuccessful =
        contract_api::transfer_from_purse_to_purse(payment_purse, source_purse, payment_amount)
    {
        contract_api::revert(Error::TransferFromPaymentToSource as u32);
    }
}
