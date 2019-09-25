//! Transfers the requested amount of motes to the first account and zero motes to the second
//! account.
#![no_std]

extern crate contract_ffi;

use contract_ffi::contract_api::{self, Error as ApiError, TransferResult};
use contract_ffi::value::account::PublicKey;
use contract_ffi::value::U512;

enum Arg {
    Account1PublicKey = 0,
    Account1Amount = 1,
    Account2PublicKey = 2,
}

#[repr(u16)]
enum Error {
    AccountAlreadyExists = 0,
}

fn create_account_with_amount(account: PublicKey, amount: U512) {
    match contract_api::transfer_to_account(account, amount) {
        TransferResult::TransferredToNewAccount => (),
        TransferResult::TransferredToExistingAccount => {
            contract_api::revert(ApiError::User(Error::AccountAlreadyExists as u16).into())
        }
        TransferResult::TransferError => contract_api::revert(ApiError::Transfer.into()),
    }
}

#[no_mangle]
pub extern "C" fn call() {
    let public_key1: PublicKey = match contract_api::get_arg(Arg::Account1PublicKey as u32) {
        Some(Ok(data)) => data,
        Some(Err(_)) => contract_api::revert(ApiError::InvalidArgument.into()),
        None => contract_api::revert(ApiError::MissingArgument.into()),
    };
    let amount: U512 = match contract_api::get_arg(Arg::Account1Amount as u32) {
        Some(Ok(data)) => data,
        Some(Err(_)) => contract_api::revert(ApiError::InvalidArgument.into()),
        None => contract_api::revert(ApiError::MissingArgument.into()),
    };
    create_account_with_amount(public_key1, amount);

    let public_key2: PublicKey = match contract_api::get_arg(Arg::Account2PublicKey as u32) {
        Some(Ok(data)) => data,
        Some(Err(_)) => contract_api::revert(ApiError::InvalidArgument.into()),
        None => contract_api::revert(ApiError::MissingArgument.into()),
    };
    create_account_with_amount(public_key2, U512::zero());
}
