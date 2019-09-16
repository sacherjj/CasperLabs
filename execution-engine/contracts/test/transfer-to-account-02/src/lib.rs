#![no_std]
#![feature(cell_update)]

extern crate alloc;
extern crate contract_ffi;

use contract_ffi::contract_api::TransferResult;
use contract_ffi::value::account::PublicKey;
use contract_ffi::value::U512;

const ACCOUNT_2_ADDR: [u8; 32] = [2u8; 32];

#[no_mangle]
pub extern "C" fn call() {
    let public_key = PublicKey::new(ACCOUNT_2_ADDR);
    let amount: U512 = contract_ffi::contract_api::get_arg(0);

    let result = contract_ffi::contract_api::transfer_to_account(public_key, amount);

    assert_ne!(result, TransferResult::TransferError);
}
