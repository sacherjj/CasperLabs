#![no_std]
#![no_main]

use contract::{
    contract_api::{runtime, system},
    unwrap_or_revert::UnwrapOrRevert,
};
use types::{account::PublicKey, ApiError, TransferredTo, U512};

const ARG_PUBLIC_KEY: &str = "public_key";
const ARG_AMOUNT: &str = "amount";

#[repr(u16)]
enum Error {
    NonExistentAccount = 0,
}

#[no_mangle]
pub extern "C" fn call() {
    let public_key: PublicKey = runtime::get_named_arg(ARG_PUBLIC_KEY);
    let amount: U512 = runtime::get_named_arg(ARG_AMOUNT);
    match system::transfer_to_account(public_key, amount).unwrap_or_revert() {
        TransferredTo::NewAccount => {
            runtime::revert(ApiError::User(Error::NonExistentAccount as u16))
        }
        TransferredTo::ExistingAccount => (),
    }
}
