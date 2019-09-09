#![no_std]
// #![feature(cell_update)]

extern crate alloc;
extern crate contract_ffi;

use alloc::vec::Vec;
use contract_ffi::contract_api::{self, TransferResult};//;{add_uref, get_uref, new_uref};
use contract_ffi::value::U512;
use contract_ffi::value::account::PublicKey;

enum Error {
    SeedTransferFail = 100,
}

#[no_mangle]
pub extern "C" fn call() {
    let accounts: Vec<PublicKey> = {
        let data: Vec<Vec<u8>> = contract_api::get_arg(0);
        data.into_iter().map(|bytes| {
            let mut public_key_bytes = [0u8; 32];
            public_key_bytes.copy_from_slice(&bytes);
            PublicKey::new(public_key_bytes)
        }).collect()
    };

    let seed_amount: U512 = contract_api::get_arg(1);

    for public_key in accounts {
        let result = contract_ffi::contract_api::transfer_to_account(public_key, seed_amount);
        if result == TransferResult::TransferError {
            contract_api::revert(Error::SeedTransferFail as u32);
        }
    }
}
