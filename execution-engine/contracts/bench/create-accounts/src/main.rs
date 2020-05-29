#![no_std]
#![no_main]

extern crate alloc;

use alloc::vec::Vec;

use contract::{
    contract_api::{runtime, system},
    unwrap_or_revert::UnwrapOrRevert,
};
use types::{account::AccountHash, ApiError, U512};

#[no_mangle]
pub extern "C" fn call() {
    let accounts: Vec<AccountHash> = {
        let data: Vec<Vec<u8>> = runtime::get_arg(0)
            .unwrap_or_revert_with(ApiError::MissingArgument)
            .unwrap_or_revert_with(ApiError::InvalidArgument);
        data.into_iter()
            .map(|bytes| AccountHash::try_from(bytes.as_slice()).unwrap_or_revert())
            .collect()
    };
    let seed_amount: U512 = runtime::get_arg(1)
        .unwrap_or_revert_with(ApiError::MissingArgument)
        .unwrap_or_revert_with(ApiError::InvalidArgument);
    for account_hash in accounts {
        system::transfer_to_account(account_hash, seed_amount)
            .unwrap_or_revert_with(ApiError::Transfer);
    }
}
