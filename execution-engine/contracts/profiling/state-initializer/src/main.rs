//! Transfers the requested amount of motes to the first account and zero motes to the second
//! account.
#![no_std]
#![no_main]

use contract::contract_api::{runtime, system};
use types::{account::PublicKey, ApiError, TransferredTo, U512};

const ARG_ACCOUNT1_PUBLIC_KEY: &str = "account_1_public_key";
const ARG_ACCOUNT1_AMOUNT: &str = "account_1_amount";
const ARG_ACCOUNT2_PUBLIC_KEY: &str = "account_2_public_key";

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
    let public_key1: PublicKey = runtime::get_named_arg(ARG_ACCOUNT1_PUBLIC_KEY);
    let amount: U512 = runtime::get_named_arg(ARG_ACCOUNT1_AMOUNT);
    create_account_with_amount(public_key1, amount);

    let public_key2: PublicKey = runtime::get_named_arg(ARG_ACCOUNT2_PUBLIC_KEY);
    create_account_with_amount(public_key2, U512::zero());
}
