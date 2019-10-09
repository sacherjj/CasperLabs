#![no_std]

extern crate alloc;

extern crate contract_ffi;

use alloc::vec::Vec;

use contract_ffi::contract_api::{self, Error};
use contract_ffi::unwrap_or_revert::UnwrapOrRevert;
use contract_ffi::value::account::PurseId;
use contract_ffi::value::uint::U512;

#[no_mangle]
pub extern "C" fn call() {
    let pos_pointer = contract_api::system::get_proof_of_stake();

    let source_purse = contract_api::account::get_main_purse();
    let payment_amount: U512 = contract_api::runtime::get_arg::<u32>(1)
        .unwrap_or_revert_with(Error::MissingArgument)
        .unwrap_or_revert_with(Error::InvalidArgument)
        .into();
    let payment_purse: PurseId =
        contract_api::runtime::call_contract(pos_pointer, &("get_payment_purse",), &Vec::new());

    // can deposit
    contract_api::system::transfer_from_purse_to_purse(source_purse, payment_purse, payment_amount)
        .unwrap_or_revert_with(Error::User(1));

    let payment_balance =
        contract_api::system::get_balance(payment_purse).unwrap_or_revert_with(Error::User(3));

    if payment_balance != payment_amount {
        contract_api::runtime::revert(Error::User(4))
    }

    // cannot withdraw
    if contract_api::system::transfer_from_purse_to_purse(
        payment_purse,
        source_purse,
        payment_amount,
    )
    .is_ok()
    {
        contract_api::runtime::revert(Error::User(5));
    }
}
