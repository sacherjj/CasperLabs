#![no_std]
#![feature(alloc, cell_update)]

extern crate alloc;
extern crate cl_std;

use cl_std::contract_api::{get_arg, revert, add_associated_key};
use cl_std::value::account::{PublicKey, Weight};

#[no_mangle]
pub extern "C" fn call() {
    let account: PublicKey = get_arg(0);
    // TODO: Update when u8 is supported by ABI for tests
    let weight_val: u32 = get_arg(1);
    let weight = Weight::new(weight_val as u8);

    add_associated_key(account, weight)
        .unwrap_or_else(|_| revert(100));
}
