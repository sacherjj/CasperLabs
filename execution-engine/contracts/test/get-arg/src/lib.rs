#![no_std]

extern crate alloc;
extern crate contract_ffi;

use alloc::string::String;
use contract_ffi::contract_api::{self, Error as ApiError};
use contract_ffi::value::U512;

#[repr(u16)]
enum Error {
    MissingArgument0 = 0,
    MissingArgument1,
    InvalidArgument0,
    InvalidArgument1,
}

#[no_mangle]
pub extern "C" fn call() {
    let value0: String = match contract_api::get_arg(0) {
        Some(Ok(data)) => data,
        Some(Err(_)) => contract_api::revert(ApiError::User(Error::InvalidArgument0 as u16).into()),
        None => contract_api::revert(ApiError::User(Error::MissingArgument0 as u16).into()),
    };
    assert_eq!(value0, "Hello, world!");

    let value1: U512 = match contract_api::get_arg(1) {
        Some(Ok(data)) => data,
        Some(Err(_)) => contract_api::revert(ApiError::User(Error::InvalidArgument1 as u16).into()),
        None => contract_api::revert(ApiError::User(Error::MissingArgument1 as u16).into()),
    };
    assert_eq!(value1, U512::from(42));
}
