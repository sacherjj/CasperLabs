#![no_std]

extern crate alloc;
extern crate contract_ffi;
extern crate contracts_common;

use alloc::vec::Vec;

use contract_ffi::contract_api::{self, PurseTransferResult};
use contract_ffi::value::account::PurseId;
use contract_ffi::value::uint::U512;
use contracts_common::RESERVED_ERROR_MAX;

enum Error {
    TransferFromSourceToPayment = RESERVED_ERROR_MAX as isize + 1,
    TransferFromPaymentToSource = RESERVED_ERROR_MAX as isize + 2,
    GetBalance = RESERVED_ERROR_MAX as isize + 3,
    CheckBalance = RESERVED_ERROR_MAX as isize + 4,
}

#[no_mangle]
pub extern "C" fn call() {
    let pos_pointer = contracts_common::get_pos_contract_read_only();
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
