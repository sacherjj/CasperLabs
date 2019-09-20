//! Transfers the requested amount of motes to the first account and zero motes to the second
//! account.
#![no_std]

extern crate contract_ffi;

use contract_ffi::contract_api::{self, TransferResult};
use contract_ffi::value::account::PublicKey;
use contract_ffi::value::U512;

enum Arg {
    Account1PublicKey = 0,
    Account1Amount = 1,
    Account2PublicKey = 2,
}

enum Error {
    AccountAlreadyExists = 1,
    Transfer = 2,
    MissingArgument = 100,
    InvalidArgument = 101,
}

fn create_account_with_amount(account: PublicKey, amount: U512) {
    match contract_api::transfer_to_account(account, amount) {
        TransferResult::TransferredToNewAccount => (),
        TransferResult::TransferredToExistingAccount => {
            contract_api::revert(Error::AccountAlreadyExists as u32)
        }
        TransferResult::TransferError => contract_api::revert(Error::Transfer as u32),
    }
}

#[no_mangle]
pub extern "C" fn call() {
    let public_key1: PublicKey = contract_api::get_arg(Arg::Account1PublicKey as u32)
        .unwrap_or_else(|| contract_api::revert(Error::MissingArgument as u32))
        .unwrap_or_else(|_| contract_api::revert(Error::InvalidArgument as u32));
    let amount: U512 = contract_api::get_arg(Arg::Account1Amount as u32)
        .unwrap_or_else(|| contract_api::revert(Error::MissingArgument as u32))
        .unwrap_or_else(|_| contract_api::revert(Error::InvalidArgument as u32));
    create_account_with_amount(public_key1, amount);

    let public_key2: PublicKey = contract_api::get_arg(Arg::Account2PublicKey as u32)
        .unwrap_or_else(|| contract_api::revert(Error::MissingArgument as u32))
        .unwrap_or_else(|_| contract_api::revert(Error::InvalidArgument as u32));
    create_account_with_amount(public_key2, U512::zero());
}
