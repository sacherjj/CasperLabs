#![no_std]
#![feature(alloc, cell_update)]

extern crate alloc;
extern crate cl_std;

use cl_std::contract_api::TransferResult;
use cl_std::value::account::PublicKey;
use cl_std::value::U512;

#[no_mangle]
pub extern "C" fn call() {
    let public_key = PublicKey::new([7u8; 32]);
    let amount = U512::from_dec_str("1000").expect("should create U512");

    let result = cl_std::contract_api::transfer_to_account(public_key, amount);

    assert_eq!(result, TransferResult::TransferredToNewAccount);
}
