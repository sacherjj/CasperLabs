#![no_std]

extern crate alloc;
extern crate contract_ffi;

use alloc::vec::Vec;

use contract_ffi::contract_api::{self, PurseTransferResult};
use contract_ffi::value::account::PurseId;
use contract_ffi::value::uint::U512;

enum Error {
    GetPosURef = 1,
    TransferFromSourceToPayment = 2,
    TransferFromPaymentToSource = 3,
    GetBalance = 4,
    CheckBalance = 5,
}

#[no_mangle]
pub extern "C" fn call() {
    let pos_pointer =
        contract_api::get_pos().unwrap_or_else(|| contract_api::revert(Error::GetPosURef as u32));

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
