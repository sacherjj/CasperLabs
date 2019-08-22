#![no_std]
#![feature(alloc, cell_update)]

extern crate alloc;

extern crate cl_std;

use cl_std::contract_api::{get_arg, transfer_to_account, TransferResult};
use cl_std::value::account::PublicKey;
use cl_std::value::U512;

#[no_mangle]
pub extern "C" fn call() {
    let amount: U512 = get_arg(0);

    let public_key = PublicKey::new([42; 32]);
    let result = transfer_to_account(public_key, amount);
    assert_eq!(result, TransferResult::TransferError)
}
