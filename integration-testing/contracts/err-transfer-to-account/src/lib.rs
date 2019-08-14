
#![no_std]
#![feature(alloc, cell_update)]

extern crate alloc;
extern crate cl_std;

use cl_std::contract_api::{revert};

#[no_mangle]
pub extern "C" fn call() {
    revert(99);
}
