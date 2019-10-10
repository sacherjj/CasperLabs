#![no_std]

extern crate contract_ffi;

use contract_ffi::contract_api::{runtime, Error};
use contract_ffi::unwrap_or_revert::UnwrapOrRevert;

#[no_mangle]
pub extern "C" fn call() {
    let number: u32 = runtime::get_arg(0)
        .unwrap_or_revert_with(Error::MissingArgument)
        .unwrap_or_revert_with(Error::InvalidArgument);
    runtime::revert(Error::User(number as u16));
}
