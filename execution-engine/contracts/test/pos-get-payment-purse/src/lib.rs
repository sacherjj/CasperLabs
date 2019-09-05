#![no_std]

extern crate alloc;
extern crate contract_ffi;

use alloc::vec::Vec;

use contract_ffi::contract_api::pointers::{ContractPointer, UPointer};
use contract_ffi::contract_api::{self, PurseTransferResult};
use contract_ffi::key::Key;
use contract_ffi::uref::AccessRights;
use contract_ffi::value::account::PurseId;
use contract_ffi::value::uint::U512;

enum Error {
    GetPosOuterURef = 1,
    GetPosInnerURef = 2,
    TransferFromSourceToPayment = 3,
    TransferFromPaymentToSource = 4,
    GetBalance = 5,
    CheckBalance = 6,
}

#[no_mangle]
pub extern "C" fn call() {
    let pos_pointer = {
        let outer: UPointer<Key> = contract_api::get_uref("pos")
            .and_then(Key::to_u_ptr)
            .unwrap_or_else(|| contract_api::revert(Error::GetPosInnerURef as u32));
        if let Some(ContractPointer::URef(inner)) = contract_api::read::<Key>(outer).to_c_ptr() {
            ContractPointer::URef(UPointer::new(inner.0, AccessRights::READ))
        } else {
            contract_api::revert(Error::GetPosOuterURef as u32);
        }
    };

    let source_purse = contract_api::main_purse();
    let payment_amount: U512 = 100.into();
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

    if payment_balance != payment_amount {
        contract_api::revert(Error::CheckBalance as u32)
    }

    // cannot withdraw
    if let PurseTransferResult::TransferSuccessful =
        contract_api::transfer_from_purse_to_purse(payment_purse, source_purse, payment_amount)
    {
        contract_api::revert(Error::TransferFromPaymentToSource as u32);
    }
}
