#![no_std]
#![feature(alloc, cell_update)]

extern crate alloc;
extern crate cl_std;

use cl_std::contract_api::TransferResult;
use cl_std::value::account::PublicKey;
use cl_std::value::U512;

const ACCOUNT_2_ADDR: [u8; 32] = [2u8; 32];
const TRANSFER_AMOUNT: u32 = 75;

#[no_mangle]
pub extern "C" fn call() {
    let public_key = PublicKey::new(ACCOUNT_2_ADDR);
    let amount = U512::from(TRANSFER_AMOUNT);

    let result = cl_std::contract_api::transfer_to_account(public_key, amount);

    assert_ne!(result, TransferResult::TransferError);
}
