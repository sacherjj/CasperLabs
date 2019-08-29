
#![no_std]
#![feature(cell_update)]

extern crate alloc;
extern crate contract_ffi;

use contract_ffi::value::account::PublicKey;
use contract_ffi::value::U512;
use contract_ffi::contract_api::{get_arg, revert, TransferResult};

#[no_mangle]
pub extern "C" fn call() {
    // Call revert with an application specific non-zero exit code.
    revert(1);
}
