#![no_std]

extern crate alloc;

extern crate contract_ffi;

use alloc::vec::Vec;
use core::convert::TryFrom;

use contract_ffi::contract_api::{self, Error};
use contract_ffi::unwrap_or_revert::UnwrapOrRevert;
use contract_ffi::value::account::PublicKey;
use contract_ffi::value::U512;

#[no_mangle]
pub extern "C" fn call() {
    let accounts: Vec<PublicKey> = {
        let data: Vec<Vec<u8>> = contract_api::get_arg(0)
            .unwrap_or_revert_with(Error::MissingArgument)
            .unwrap_or_revert_with(Error::InvalidArgument);
        data.into_iter()
            .map(|bytes| PublicKey::try_from(bytes.as_slice()).unwrap_or_revert())
            .collect()
    };
    let seed_amount: U512 = contract_api::get_arg(1)
        .unwrap_or_revert_with(Error::MissingArgument)
        .unwrap_or_revert_with(Error::InvalidArgument);
    for public_key in accounts {
        contract_ffi::contract_api::transfer_to_account(public_key, seed_amount)
            .unwrap_or_revert_with(Error::Transfer);
    }
}
