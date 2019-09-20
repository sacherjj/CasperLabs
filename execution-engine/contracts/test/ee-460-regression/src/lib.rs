#![no_std]
#![feature(cell_update)]

extern crate alloc;

extern crate contract_ffi;

use contract_ffi::contract_api::{get_arg, revert, transfer_to_account, TransferResult};
use contract_ffi::value::account::PublicKey;
use contract_ffi::value::U512;

enum Error {
    MissingArgument = 100,
    InvalidArgument = 101,
}

#[no_mangle]
pub extern "C" fn call() {
    let amount: U512 = get_arg(0)
        .unwrap_or_else(|| revert(Error::MissingArgument as u32))
        .unwrap_or_else(|_| revert(Error::InvalidArgument as u32));

    let public_key = PublicKey::new([42; 32]);
    let result = transfer_to_account(public_key, amount);
    assert_eq!(result, TransferResult::TransferError)
}
