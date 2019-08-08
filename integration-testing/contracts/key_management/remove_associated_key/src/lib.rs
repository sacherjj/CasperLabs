#![no_std]
#![feature(alloc, cell_update)]

extern crate alloc;
extern crate cl_std;

use cl_std::contract_api;
use cl_std::value::account::PublicKey;

#[no_mangle]
pub extern "C" fn call() {
    let account: PublicKey = contract_api::get_arg(0);
    contract_api::remove_associated_key(account)
        .unwrap_or_else(|_| contract_api::revert(1));
}
