#![no_std]
#![feature(alloc, cell_update)]

extern crate alloc;
extern crate cl_std;

use cl_std::contract_api::{get_arg, revert};
use cl_std::value::{U512};


#[no_mangle]
pub extern "C" fn call() {
    let number: U512 = get_arg(0);

    // I do this silly looping because I don't know how to convert U512 to a native Rust int.
    for i in 0..1025 {
        if number == U512::from(i) {
            revert(i);
        }
    }
}
