#![no_std]
// #![feature(cell_update)]

extern crate alloc;
extern crate contract_ffi;

use contract_ffi::contract_api::{self, TransferResult}; //;{add_uref, get_uref, new_uref};
use contract_ffi::value::account::PublicKey;
use contract_ffi::value::U512;

enum Error {
    TransferredToNewAccount = 100,
    TransferError = 101,
}

#[no_mangle]
pub extern "C" fn call() {
    let public_key: PublicKey = contract_api::get_arg(0);
    let amount: U512 = contract_api::get_arg(1);
    let result = contract_ffi::contract_api::transfer_to_account(public_key, amount);
    match result {
        TransferResult::TransferredToExistingAccount => {
            // This is the expected result, as all accounts have to be initialized beforehand
        }
        TransferResult::TransferredToNewAccount => {
            contract_api::revert(Error::TransferredToNewAccount as u32)
        }
        TransferResult::TransferError => contract_api::revert(Error::TransferError as u32),
    }
}
