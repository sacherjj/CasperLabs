#![no_std]

extern crate alloc;

use alloc::string::String;

use contract::{contract_api::runtime, unwrap_or_revert::UnwrapOrRevert};
use types::{ApiError, U512};

#[repr(u16)]
enum Error {
    MissingArgument0 = 0,
    MissingArgument1,
    InvalidArgument0,
    InvalidArgument1,
}

#[no_mangle]
pub extern "C" fn call() {
    let value0: String = runtime::get_arg(0)
        .unwrap_or_revert_with(ApiError::User(Error::MissingArgument0 as u16))
        .unwrap_or_revert_with(ApiError::User(Error::InvalidArgument0 as u16));
    assert_eq!(value0, "Hello, world!");

    let value1: U512 = runtime::get_arg(1)
        .unwrap_or_revert_with(ApiError::User(Error::MissingArgument1 as u16))
        .unwrap_or_revert_with(ApiError::User(Error::InvalidArgument1 as u16));
    assert_eq!(value1, U512::from(42));
}
