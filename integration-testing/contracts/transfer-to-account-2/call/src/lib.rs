#![no_std]
#![feature(alloc, cell_update)]

extern crate alloc;
extern crate cl_std;

use cl_std::contract_api::TransferResult;
use cl_std::value::account::PublicKey;
use cl_std::value::U512;

const ACCOUNT_2_ADDR: [u8; 32] = [78, 116, 110, 103, 206, 246, 55, 160, 116, 3, 157, 96, 36, 126, 92, 38, 5,
                                  217, 165, 215, 142, 199, 170, 196, 106, 131, 204, 227, 129, 6, 157, 53];
const TRANSFER_AMOUNT: u32 = 75;

#[no_mangle]
pub extern "C" fn call() {
    let public_key = PublicKey::new(ACCOUNT_2_ADDR);
    let amount = U512::from(TRANSFER_AMOUNT);

    let result = cl_std::contract_api::transfer_to_account(public_key, amount);

    assert_ne!(result, TransferResult::TransferError);
}
