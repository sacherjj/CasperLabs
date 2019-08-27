
#![no_std]
#![feature(cell_update)]

extern crate alloc;
extern crate cl_std;

use cl_std::value::account::PublicKey;
use cl_std::value::U512;
use cl_std::contract_api::{get_arg, revert, TransferResult};

#[no_mangle]
pub extern "C" fn call() {
    // Call revert with an application specific non-zero exit code.
    revert(1);
}
