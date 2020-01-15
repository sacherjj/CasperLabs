//! Transfers the requested amount of motes to the first account and zero motes to the second
//! account.
#![no_std]

use contract::{
    contract_api::{runtime, system},
    unwrap_or_revert::UnwrapOrRevert,
};
use types::{account::PublicKey, ApiError, TransferredTo, U512};

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
    match system::transfer_to_account(account, amount) {
        Ok(TransferredTo::NewAccount) => (),
        Ok(TransferredTo::ExistingAccount) => {
            runtime::revert(ApiError::User(Error::AccountAlreadyExists as u16))
        }
        Err(_) => runtime::revert(ApiError::Transfer),
    }
}

#[no_mangle]
pub extern "C" fn call() {
    let public_key1: PublicKey = runtime::get_arg(Arg::Account1PublicKey as u32)
        .unwrap_or_revert_with(ApiError::MissingArgument)
        .unwrap_or_revert_with(ApiError::InvalidArgument);
    let amount: U512 = runtime::get_arg(Arg::Account1Amount as u32)
        .unwrap_or_revert_with(ApiError::MissingArgument)
        .unwrap_or_revert_with(ApiError::InvalidArgument);
    create_account_with_amount(public_key1, amount);

    let public_key2: PublicKey = runtime::get_arg(Arg::Account2PublicKey as u32)
        .unwrap_or_revert_with(ApiError::MissingArgument)
        .unwrap_or_revert_with(ApiError::InvalidArgument);
    create_account_with_amount(public_key2, U512::zero());
}
