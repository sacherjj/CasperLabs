#![no_std]
#![feature(cell_update)]

extern crate alloc;
extern crate contract_ffi;

use contract_ffi::contract_api::{get_arg, remove_associated_key, revert};
use contract_ffi::value::account::PublicKey;

#[no_mangle]
pub extern "C" fn call() {
    let account: PublicKey = get_arg(0);

    remove_associated_key(account)
        .unwrap_or_else(|_| revert(1));
}
