#![no_std]
#![feature(alloc)]

extern crate contract_ffi;

use contract_ffi::contract_api;

#[no_mangle]
pub extern "C" fn call() {
    loop {
        let _main_purse = contract_api::main_purse();
    }
}
