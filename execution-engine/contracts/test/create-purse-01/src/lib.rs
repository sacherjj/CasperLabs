#![no_std]
#![feature(cell_update)]
extern crate alloc;
extern crate contract_ffi;

use alloc::string::String;
use contract_ffi::contract_api;

enum Error {
    MissingArg = 100,
    InvalidArgument = 101,
}

#[no_mangle]
pub extern "C" fn call() {
    let purse_name: String = contract_api::get_arg(0)
        .unwrap_or_else(|| contract_api::revert(Error::MissingArg as u32))
        .unwrap_or_else(|_| contract_api::revert(Error::InvalidArgument as u32));
    let purse_id = contract_api::create_purse();
    contract_api::add_uref(&purse_name, &purse_id.value().into());
}
