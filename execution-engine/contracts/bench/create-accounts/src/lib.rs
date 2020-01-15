#![no_std]

extern crate alloc;

use alloc::vec::Vec;
use core::convert::TryFrom;

use contract::{
    contract_api::{runtime, system},
    unwrap_or_revert::UnwrapOrRevert,
};
use types::{account::PublicKey, ApiError, U512};

#[no_mangle]
pub extern "C" fn call() {
    let accounts: Vec<PublicKey> = {
        let data: Vec<Vec<u8>> = runtime::get_arg(0)
            .unwrap_or_revert_with(ApiError::MissingArgument)
            .unwrap_or_revert_with(ApiError::InvalidArgument);
        data.into_iter()
            .map(|bytes| PublicKey::try_from(bytes.as_slice()).unwrap_or_revert())
            .collect()
    };
    let seed_amount: U512 = runtime::get_arg(1)
        .unwrap_or_revert_with(ApiError::MissingArgument)
        .unwrap_or_revert_with(ApiError::InvalidArgument);
    for public_key in accounts {
        system::transfer_to_account(public_key, seed_amount)
            .unwrap_or_revert_with(ApiError::Transfer);
    }
}
