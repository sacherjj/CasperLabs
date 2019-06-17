#![no_std]
#![feature(alloc)]

extern crate alloc;

extern crate common;
use common::contract_api::*;

#[no_mangle]
pub extern "C" fn call() {
    revert(3);
}
