#![no_std]
#![feature(cell_update)]

extern crate alloc;
extern crate contract_ffi;

use contract_ffi::contract_api::{self, Error};
use contract_ffi::value::account::PublicKey;

const REMOVE_FAIL: u32 = 1;

#[no_mangle]
pub extern "C" fn call() {
    let account: PublicKey = match contract_api::get_arg(0) {
        Some(Ok(data)) => data,
        Some(Err(_)) => contract_api::revert(Error::InvalidArgument.into()),
        None => contract_api::revert(Error::MissingArgument.into()),
    };
    contract_api::remove_associated_key(account)
        .unwrap_or_else(|_| contract_api::revert(REMOVE_FAIL));
}
