#![no_std]
#![feature(alloc, cell_update)]

extern crate alloc;

extern crate cl_std;

use cl_std::contract_api::{get_arg, revert, transfer_to_account, TransferResult};
use cl_std::value::account::PublicKey;
use cl_std::value::U512;

enum ContractResult {
    TransferError = 101,
}

#[no_mangle]
pub extern "C" fn call() {
    // That will fail for sure because genesis account has less tokens,
    // and the value is 'random' enough to verify correct precondition check
    // in the engine.
    let amount: U512 = get_arg(0);

    let public_key = PublicKey::new([42; 32]);
    let result = transfer_to_account(public_key, amount);
    if result != TransferResult::TransferredToNewAccount {
        revert(ContractResult::TransferError as u32);
    }
}
