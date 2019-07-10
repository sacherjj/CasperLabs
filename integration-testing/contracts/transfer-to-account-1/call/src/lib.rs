#![no_std]
#![feature(alloc, cell_update)]

extern crate alloc;
extern crate cl_std;

use cl_std::contract_api::TransferResult;
use cl_std::value::account::PublicKey;
use cl_std::value::U512;
use cl_std::contract_api::revert;


const ACCOUNT_1_ADDR: [u8; 32] = [157, 57, 183, 251, 164, 125, 7, 193, 175, 111, 113, 30, 254, 96, 74, 17,
                                  42, 179, 113, 226, 222, 239, 185, 154, 97, 61, 43, 61, 205, 251, 164, 20];
const TRANSFER_AMOUNT: u32 = 1000000;

#[no_mangle]
pub extern "C" fn call() {
    let public_key = PublicKey::new(ACCOUNT_1_ADDR);
    let amount = U512::from(TRANSFER_AMOUNT);

    let result = cl_std::contract_api::transfer_to_account(public_key, amount);

    if result == TransferResult::TransferError {
        revert(1);
    }
}
