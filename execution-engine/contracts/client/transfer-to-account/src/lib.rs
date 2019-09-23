#![no_std]

extern crate contract_ffi;

use contract_ffi::contract_api::{self, TransferResult};
use contract_ffi::value::account::PublicKey;
use contract_ffi::value::U512;

enum Error {
    MissingArgument = 100,
    InvalidArgument = 101,
}

/// Executes mote transfer to supplied public key.
/// Transfers the requested amount.
///
/// Revert status codes:
/// 2 - transfer error
#[no_mangle]
pub extern "C" fn call() {
    let public_key: PublicKey = match contract_api::get_arg(0) {
        Some(Ok(data)) => data,
        Some(Err(_)) => contract_api::revert(Error::InvalidArgument as u32),
        None => contract_api::revert(Error::MissingArgument as u32),
    };
    let transfer_amount: u64 = match contract_api::get_arg(1) {
        Some(Ok(data)) => data,
        Some(Err(_)) => contract_api::revert(Error::InvalidArgument as u32),
        None => contract_api::revert(Error::MissingArgument as u32),
    };
    let u512_motes = U512::from(transfer_amount);
    let transfer_result = contract_api::transfer_to_account(public_key, u512_motes);
    if let TransferResult::TransferError = transfer_result {
        contract_api::revert(2);
    }
}
