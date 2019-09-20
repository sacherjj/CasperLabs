#![no_std]

extern crate alloc;
extern crate contract_ffi;

use contract_ffi::contract_api::{self, TransferResult};
use contract_ffi::value::account::PublicKey;
use contract_ffi::value::U512;

enum Arg {
    PublicKey = 0,
    Amount = 1,
}

enum Error {
    TransferredToNewAccount = 100,
    Transfer = 101,
    MissingArg = 102,
    InvalidArgument = 103,
}

#[no_mangle]
pub extern "C" fn call() {
    let public_key: PublicKey = contract_api::get_arg(Arg::PublicKey as u32)
        .unwrap_or_else(|| contract_api::revert(Error::MissingArg as u32))
        .unwrap_or_else(|_| contract_api::revert(Error::InvalidArgument as u32));
    let amount: U512 = contract_api::get_arg(Arg::Amount as u32)
        .unwrap_or_else(|| contract_api::revert(Error::MissingArg as u32))
        .unwrap_or_else(|_| contract_api::revert(Error::InvalidArgument as u32));
    let result = contract_ffi::contract_api::transfer_to_account(public_key, amount);
    match result {
        TransferResult::TransferredToExistingAccount => {
            // This is the expected result, as all accounts have to be initialized beforehand
        }
        TransferResult::TransferredToNewAccount => {
            contract_api::revert(Error::TransferredToNewAccount as u32)
        }
        TransferResult::TransferError => contract_api::revert(Error::Transfer as u32),
    }
}
