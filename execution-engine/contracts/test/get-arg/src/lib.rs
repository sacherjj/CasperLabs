#![no_std]

extern crate alloc;
extern crate contract_ffi;

use alloc::string::String;
use contract_ffi::contract_api;
use contract_ffi::value::U512;

enum Error {
    MissingArg0 = 100,
    MissingArg1 = 101,
    InvalidArgument0 = 200,
    InvalidArgument1 = 201,
}

#[no_mangle]
pub extern "C" fn call() {
    let value0: String = contract_api::get_arg(0)
        .unwrap_or_else(|| contract_api::revert(Error::MissingArg0 as u32))
        .unwrap_or_else(|_| contract_api::revert(Error::InvalidArgument0 as u32));
    assert_eq!(value0, "Hello, world!");

    let value1: U512 = contract_api::get_arg(1)
        .unwrap_or_else(|| contract_api::revert(Error::MissingArg1 as u32))
        .unwrap_or_else(|_| contract_api::revert(Error::InvalidArgument1 as u32));
    assert_eq!(value1, U512::from(42));
}
