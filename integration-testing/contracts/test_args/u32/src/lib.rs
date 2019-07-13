#![no_std]
#![feature(alloc, cell_update)]

extern crate alloc;
extern crate cl_std;

use cl_std::contract_api::{get_arg, revert};

#[no_mangle]
pub extern "C" fn call() {
    let number: u32 = get_arg(0);
    revert(number);
}
