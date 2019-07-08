#![no_std]
#![feature(alloc, cell_update)]

extern crate alloc;
extern crate cl_std;

use cl_std::contract_api::{get_arg, TransferResult};
use cl_std::value::U512;

const TRANSFER_AMOUNT: u32 = 1000;

#[no_mangle]
pub extern "C" fn call() {
    let public_key = get_arg(0);
    let amount = U512::from(TRANSFER_AMOUNT);

    let result = cl_std::contract_api::transfer_to_account(public_key, amount);

    assert_ne!(result, TransferResult::TransferError);
}
