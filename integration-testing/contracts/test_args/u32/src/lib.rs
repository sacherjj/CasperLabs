#![no_std]
#![feature(cell_update)]

extern crate alloc;
extern crate contract_ffi;

use contract_ffi::contract_api::{get_arg, revert};

#[no_mangle]
pub extern "C" fn call() {
    let number: u32 = get_arg(0);
    revert(number);
}
