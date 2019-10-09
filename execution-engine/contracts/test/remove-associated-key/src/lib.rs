#![no_std]

extern crate contract_ffi;

use contract_ffi::contract_api::{account, runtime, Error as ApiError};
use contract_ffi::unwrap_or_revert::UnwrapOrRevert;
use contract_ffi::value::account::PublicKey;

#[repr(u16)]
enum Error {
    RemoveAssociatedKey,
}

#[no_mangle]
pub extern "C" fn call() {
    let account: PublicKey = runtime::get_arg(0)
        .unwrap_or_revert_with(ApiError::MissingArgument)
        .unwrap_or_revert_with(ApiError::InvalidArgument);
    account::remove_associated_key(account)
        .unwrap_or_else(|_| runtime::revert(ApiError::User(Error::RemoveAssociatedKey as u16)));
}
