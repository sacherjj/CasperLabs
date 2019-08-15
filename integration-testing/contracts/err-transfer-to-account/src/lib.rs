
#![no_std]
#![feature(alloc, cell_update)]

extern crate alloc;
extern crate cl_std;

use cl_std::value::account::PublicKey;
use cl_std::value::U512;
use cl_std::contract_api::{get_arg, revert, TransferResult};

#[no_mangle]
pub extern "C" fn call() {
    let transfer_amount: u32 = get_arg(0);
    let account_addr: [u8; 32] = get_arg(1);

    let public_key = PublicKey::new(account_addr);
    let amount = U512::from(transfer_amount);
    panic!("Panicked!!!!!!");
    /*let result = cl_std::contract_api::transfer_to_account(public_key, amount);
    if result == TransferResult::TransferError {
        revert(1);
    }*/
}
