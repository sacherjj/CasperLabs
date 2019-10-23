#![no_std]

extern crate contract_ffi;

use contract_ffi::contract_api::{runtime, system, Error as ApiError};
use contract_ffi::unwrap_or_revert::UnwrapOrRevert;
use contract_ffi::value::account::PublicKey;
use contract_ffi::value::U512;

enum Arg {
    AccountAddr = 0,
    TransferAmount,
}

#[repr(u16)]
enum Error {
    TransferToAccount = 1,
}

impl Into<ApiError> for Error {
    fn into(self) -> ApiError {
        ApiError::User(self as u16)
    }
}

#[no_mangle]
pub extern "C" fn call() {
    let account_addr: [u8; 32] = runtime::get_arg(Arg::AccountAddr as u32)
        .unwrap_or_revert_with(ApiError::MissingArgument)
        .unwrap_or_revert_with(ApiError::InvalidArgument);
    let transfer_amount: u32 = runtime::get_arg(Arg::TransferAmount as u32)
        .unwrap_or_revert_with(ApiError::MissingArgument)
        .unwrap_or_revert_with(ApiError::InvalidArgument);

    let public_key = PublicKey::new(account_addr);
    let amount = U512::from(transfer_amount);

    system::transfer_to_account(public_key, amount).unwrap_or_revert_with(Error::TransferToAccount);
}
