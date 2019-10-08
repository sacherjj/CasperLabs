#![no_std]

extern crate contract_ffi;

use contract_ffi::contract_api::{self, Error as ApiError};
use contract_ffi::unwrap_or_revert::UnwrapOrRevert;
use contract_ffi::value::account::PublicKey;

#[repr(u16)]
enum Error {
    RemoveAssociatedKey,
}

#[no_mangle]
pub extern "C" fn call() {
    let account: PublicKey = contract_api::get_arg(0)
        .unwrap_or_revert_with(ApiError::MissingArgument)
        .unwrap_or_revert_with(ApiError::InvalidArgument);
    contract_api::remove_associated_key(account).unwrap_or_else(|_| {
        contract_api::revert(ApiError::User(Error::RemoveAssociatedKey as u16))
    });
}
