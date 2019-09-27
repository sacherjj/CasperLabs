#![no_std]
#![feature(cell_update)]

extern crate alloc;
extern crate contract_ffi;

use contract_ffi::contract_api::{self, Error};
use contract_ffi::value::account::PublicKey;

#[no_mangle]
pub extern "C" fn call() {
    let known_public_key: PublicKey = match contract_api::get_arg(0) {
        Some(Ok(data)) => data,
        Some(Err(_)) => contract_api::revert(Error::InvalidArgument.into()),
        None => contract_api::revert(Error::MissingArgument.into()),
    };
    let caller_public_key: PublicKey = contract_api::get_caller();
    assert_eq!(
        caller_public_key, known_public_key,
        "caller public key was not known public key"
    );
}
