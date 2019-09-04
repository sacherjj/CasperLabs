#![no_std]
#![feature(cell_update)]
extern crate alloc;
extern crate contract_ffi;

use alloc::string::String;
use contract_ffi::contract_api;

#[no_mangle]
pub extern "C" fn call() {
    let purse_name: String = contract_api::get_arg(0);
    let purse_id = contract_api::create_purse();
    contract_api::add_uref(&purse_name, &purse_id.value().into());
}
