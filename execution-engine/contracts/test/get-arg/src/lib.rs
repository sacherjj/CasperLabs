#![no_std]

extern crate alloc;
extern crate contract_ffi;

use alloc::string::String;
use contract_ffi::contract_api;
use contract_ffi::value::U512;

enum Error {
    MissingArgument0 = 100,
    MissingArgument1 = 101,
    InvalidArgument0 = 200,
    InvalidArgument1 = 201,
}

#[no_mangle]
pub extern "C" fn call() {
    let value0: String = match contract_api::get_arg(0) {
        Some(Ok(data)) => data,
        Some(Err(_)) => contract_api::revert(Error::InvalidArgument0 as u32),
        None => contract_api::revert(Error::MissingArgument0 as u32),
    };
    assert_eq!(value0, "Hello, world!");

    let value1: U512 = match contract_api::get_arg(1) {
        Some(Ok(data)) => data,
        Some(Err(_)) => contract_api::revert(Error::InvalidArgument1 as u32),
        None => contract_api::revert(Error::MissingArgument1 as u32),
    };
    assert_eq!(value1, U512::from(42));
}
