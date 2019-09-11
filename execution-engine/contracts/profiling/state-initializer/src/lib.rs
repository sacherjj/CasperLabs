//! Transfers the requested amount of motes to the first account and zero motes to the second
//! account.
#![no_std]

extern crate contract_ffi;

use contract_ffi::contract_api::{self, TransferResult};
use contract_ffi::value::account::PublicKey;
use contract_ffi::value::U512;

enum Error {
    AccountAlreadyExists = 1,
    TransferError = 2,
}

fn create_account_with_amount(account: PublicKey, amount: U512) {
    match contract_api::transfer_to_account(account, amount) {
        TransferResult::TransferredToNewAccount => (),
        TransferResult::TransferredToExistingAccount => {
            contract_api::revert(Error::AccountAlreadyExists as u32)
        }
        TransferResult::TransferError => contract_api::revert(Error::TransferError as u32),
    }
}

#[no_mangle]
pub extern "C" fn call() {
    let public_key1: PublicKey = contract_api::get_arg(0);
    let amount: U512 = contract_api::get_arg(1);
    create_account_with_amount(public_key1, amount);

    let public_key2: PublicKey = contract_api::get_arg(2);
    create_account_with_amount(public_key2, 0.into());
}
