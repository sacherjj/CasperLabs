#![no_std]
#![feature(cell_update)]

extern crate alloc;
extern crate contract_ffi;

use contract_ffi::contract_api;
use contract_ffi::value::account::PublicKey;

const REMOVE_FAIL: u32 = 1;

enum Error {
    MissingArgument = 100,
    InvalidArgument = 101,
}

#[no_mangle]
pub extern "C" fn call() {
    let account: PublicKey = contract_api::get_arg(0)
        .unwrap_or_else(|| contract_api::revert(Error::MissingArgument as u32))
        .unwrap_or_else(|_| contract_api::revert(Error::InvalidArgument as u32));
    contract_api::remove_associated_key(account)
        .unwrap_or_else(|_| contract_api::revert(REMOVE_FAIL));
}
