#![no_std]

extern crate contract_ffi;

use contract_ffi::contract_api::{runtime, Error};
use contract_ffi::unwrap_or_revert::UnwrapOrRevert;
use contract_ffi::value::U512;

#[no_mangle]
pub extern "C" fn call() {
    let number: U512 = runtime::get_arg(0)
        .unwrap_or_revert_with(Error::MissingArgument)
        .unwrap_or_revert_with(Error::InvalidArgument);

    // I do this silly looping because I don't know how to convert U512 to a native Rust int.
    for i in 0..1025 {
        if number == U512::from(i) {
            runtime::revert(Error::User(i));
        }
    }
}
