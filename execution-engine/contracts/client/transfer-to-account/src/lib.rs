#![no_std]

extern crate cl_std;

use cl_std::contract_api::{self, TransferResult};
use cl_std::value::account::PublicKey;
use cl_std::value::U512;

/// Executes token transfer to supplied public key.
/// Transfers the requested amount.
///
/// Revert status codes:
/// 2 - transfer error
#[no_mangle]
pub extern "C" fn call() {
    let public_key: PublicKey = contract_api::get_arg(0);
    let transfer_amount: u64 = contract_api::get_arg(1);
    let u512_tokens = U512::from(transfer_amount);
    let transfer_result = contract_api::transfer_to_account(public_key, u512_tokens);
    if let TransferResult::TransferError = transfer_result {
        contract_api::revert(2);
    }
}
