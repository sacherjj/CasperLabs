#![no_std]

use contract::{
    contract_api::{account, runtime, system},
    unwrap_or_revert::UnwrapOrRevert,
};
use types::{account::PurseId, ApiError, U512};

#[repr(u16)]
enum Error {
    TransferFromSourceToPayment = 0,
    TransferFromPaymentToSource,
    GetBalance,
    CheckBalance,
}

#[no_mangle]
pub extern "C" fn call() {
    let pos_pointer = system::get_proof_of_stake();
    let source_purse = account::get_main_purse();
    let payment_amount: U512 = 100.into();
    // amount passed to payment contract
    let payment_fund: U512 = runtime::get_arg(0)
        .unwrap_or_revert_with(ApiError::MissingArgument)
        .unwrap_or_revert_with(ApiError::InvalidArgument);
    let payment_purse: PurseId = runtime::call_contract(pos_pointer, ("get_payment_purse",));

    // can deposit
    system::transfer_from_purse_to_purse(source_purse, payment_purse, payment_amount)
        .unwrap_or_revert_with(ApiError::User(Error::TransferFromSourceToPayment as u16));

    let payment_balance = system::get_balance(payment_purse)
        .unwrap_or_revert_with(ApiError::User(Error::GetBalance as u16));

    if payment_balance.saturating_sub(payment_fund) != payment_amount {
        runtime::revert(ApiError::User(Error::CheckBalance as u16))
    }

    // cannot withdraw
    if system::transfer_from_purse_to_purse(payment_purse, source_purse, payment_amount).is_ok() {
        runtime::revert(ApiError::User(Error::TransferFromPaymentToSource as u16));
    }
}
