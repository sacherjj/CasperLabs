//! Transfers the requested amount of motes to the first account and zero motes to the second
//! account.
#![no_std]
#![no_main]

use contract::{
    contract_api::{runtime, system},
    unwrap_or_revert::UnwrapOrRevert,
};
use types::{account::AccountHash, ApiError, TransferredTo, U512};

enum Arg {
    Account1Hash = 0,
    Account1Amount = 1,
    Account2Hash = 2,
}

#[repr(u16)]
enum Error {
    AccountAlreadyExists = 0,
}

fn create_account_with_amount(account: AccountHash, amount: U512) {
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
    let account_hash1: AccountHash = runtime::get_arg(Arg::Account1Hash as u32)
        .unwrap_or_revert_with(ApiError::MissingArgument)
        .unwrap_or_revert_with(ApiError::InvalidArgument);
    let amount: U512 = runtime::get_arg(Arg::Account1Amount as u32)
        .unwrap_or_revert_with(ApiError::MissingArgument)
        .unwrap_or_revert_with(ApiError::InvalidArgument);
    create_account_with_amount(account_hash1, amount);

    let account_hash2: AccountHash = runtime::get_arg(Arg::Account2Hash as u32)
        .unwrap_or_revert_with(ApiError::MissingArgument)
        .unwrap_or_revert_with(ApiError::InvalidArgument);
    create_account_with_amount(account_hash2, U512::zero());
}
