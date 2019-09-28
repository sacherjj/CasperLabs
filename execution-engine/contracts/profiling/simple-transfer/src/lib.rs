#![no_std]

extern crate contract_ffi;

use contract_ffi::contract_api::{self, Error as ApiError, TransferResult};
use contract_ffi::value::account::PublicKey;
use contract_ffi::value::U512;

enum Arg {
    PublicKey = 0,
    Amount = 1,
}

#[repr(u16)]
enum Error {
    NonExistentAccount = 0,
}

#[no_mangle]
pub extern "C" fn call() {
    let public_key: PublicKey = match contract_api::get_arg(Arg::PublicKey as u32) {
        Some(Ok(data)) => data,
        Some(Err(_)) => contract_api::revert(ApiError::InvalidArgument.into()),
        None => contract_api::revert(ApiError::MissingArgument.into()),
    };
    let amount: U512 = match contract_api::get_arg(Arg::Amount as u32) {
        Some(Ok(data)) => data,
        Some(Err(_)) => contract_api::revert(ApiError::InvalidArgument.into()),
        None => contract_api::revert(ApiError::MissingArgument.into()),
    };
    match contract_api::transfer_to_account(public_key, amount) {
        TransferResult::TransferredToNewAccount => {
            contract_api::revert(ApiError::User(Error::NonExistentAccount as u16).into())
        }
        TransferResult::TransferredToExistingAccount => (),
        TransferResult::TransferError => contract_api::revert(ApiError::Transfer.into()),
    }
}
