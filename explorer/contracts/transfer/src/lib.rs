#![no_std]
#![feature(cell_update)]

extern crate alloc;
extern crate contract_ffi;

use contract_ffi::contract_api::{get_arg, revert, transfer_to_account, TransferResult};
use contract_ffi::value::account::PublicKey;
use contract_ffi::value::U512;

/// Executes token transfer to supplied public key.
/// Transfers the requested amount.
///
/// Revert status codes:
/// 2 - transfer error. (In the future it will have more granular statuses).
#[no_mangle]
pub extern "C" fn call() {
    let public_key: PublicKey = get_arg(0);
    let transfer_amount: u64 = get_arg(1);
    let u512_tokens = U512::from(transfer_amount);
    match transfer_to_account(public_key, U512::from(u512_tokens)) {
        TransferResult::TransferError => revert(2),
        _ => {
            // Transfer successful
        }
    }
}
