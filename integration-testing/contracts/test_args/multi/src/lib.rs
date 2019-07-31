#![no_std]
#![feature(alloc, cell_update)]

extern crate alloc;
extern crate cl_std;

use cl_std::contract_api::{get_arg, revert};

#[no_mangle]
pub extern "C" fn call() {
    let account_number: [u8; 32] = get_arg(0);
    let number: u32 = get_arg(1);

    let account_sum: u8 = account_number.iter().sum();
    let total_sum: u32 = account_sum as u32 + number;

    revert(total_sum);
}
