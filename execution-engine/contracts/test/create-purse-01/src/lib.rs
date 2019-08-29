#![no_std]
#![feature(cell_update)]
extern crate alloc;
extern crate contract_ffi;

use contract_ffi::contract_api;

#[no_mangle]
pub extern "C" fn call() {
    let actual_purse_id = contract_api::create_purse();

    contract_api::add_uref("actual_purse_id", &actual_purse_id.value().into());
}
