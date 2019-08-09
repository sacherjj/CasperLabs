#![no_std]
#![feature(alloc, cell_update)]

extern crate alloc;
extern crate cl_std;

use cl_std::contract_api::{get_arg, remove_associated_key, revert};
use cl_std::value::account::PublicKey;

#[no_mangle]
pub extern "C" fn call() {
    let account: PublicKey = get_arg(0);
    remove_associated_key(account)
        .unwrap_or_else(|_| revert(1));
}
