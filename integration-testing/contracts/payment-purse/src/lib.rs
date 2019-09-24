#![no_std]

extern crate alloc;
extern crate contract_ffi;
use alloc::vec::Vec;
use contract_ffi::contract_api::{self, PurseTransferResult};
use contract_ffi::value::account::PurseId;
use contract_ffi::value::uint::U512;

#[no_mangle]
pub extern "C" fn call() {
    let pos_pointer = contract_api::get_pos();

    let source_purse = contract_api::main_purse();
    let payment_amount: U512 = U512::from(contract_api::get_arg::<u32>(1));
    let payment_purse: PurseId =
        contract_api::call_contract(pos_pointer, &("get_payment_purse",), &Vec::new());

    // can deposit
    if let PurseTransferResult::TransferError =
        contract_api::transfer_from_purse_to_purse(source_purse, payment_purse, payment_amount)
    {
        contract_api::revert(2);
    }

    let payment_balance = match contract_api::get_balance(payment_purse) {
        Some(amount) => amount,
        None => contract_api::revert(3),
    };

    if payment_balance != payment_amount {
        contract_api::revert(4)
    }

    // cannot withdraw
    if let PurseTransferResult::TransferSuccessful =
        contract_api::transfer_from_purse_to_purse(payment_purse, source_purse, payment_amount)
    {
        contract_api::revert(5);
    }
}
