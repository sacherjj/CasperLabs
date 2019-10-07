#![no_std]
#![feature(cell_update)]

extern crate alloc;
extern crate contract_ffi;

use contract_ffi::contract_api::{self, Error as ApiError};
use contract_ffi::value::account::PublicKey;

#[repr(u16)]
enum Error {
    RemoveAssociatedKey,
}

#[no_mangle]
pub extern "C" fn call() {
    let account: PublicKey = match contract_api::get_arg(0) {
        Some(Ok(data)) => data,
        Some(Err(_)) => contract_api::revert(ApiError::InvalidArgument),
        None => contract_api::revert(ApiError::MissingArgument),
    };
    contract_api::remove_associated_key(account).unwrap_or_else(|_| {
        contract_api::revert(ApiError::User(Error::RemoveAssociatedKey as u16))
    });
}
