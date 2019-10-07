#![no_std]

extern crate alloc;
extern crate contract_ffi;
use contract_ffi::contract_api::{revert, Error};

#[no_mangle]
pub extern "C" fn call() {
    revert(Error::User(1));
}
