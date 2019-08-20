#![no_std]
#![feature(alloc, cell_update)]

extern crate alloc;

extern crate cl_std;

use cl_std::contract_api::{transfer_to_account, TransferResult};
use cl_std::value::account::PublicKey;
use cl_std::value::U512;

#[no_mangle]
pub extern "C" fn call() {
    // That will fail for sure because genesis account has less tokens,
    // and the value is 'random' enough to verify correct precondition check
    // in the engine.
    let mut amount = U512::max_value();
    amount -= 42u64.into();

    let public_key = PublicKey::new([42; 32]);
    let result = transfer_to_account(public_key, amount);
    assert_eq!(result, TransferResult::TransferError);
}
