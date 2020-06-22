#![no_std]
#![no_main]

extern crate alloc;

use alloc::vec::Vec;

use contract::{
    contract_api::{runtime, system},
    unwrap_or_revert::UnwrapOrRevert,
};
use types::{account::PublicKey, ApiError, U512};

const ARG_ACCOUNTS: &str = "accounts";
const ARG_SEED_AMOUNT: &str = "seed_amount";

#[no_mangle]
pub extern "C" fn call() {
    let accounts: Vec<PublicKey> = runtime::get_named_arg(ARG_ACCOUNTS);
    let seed_amount: U512 = runtime::get_named_arg(ARG_SEED_AMOUNT);
    for public_key in accounts {
        system::transfer_to_account(public_key, seed_amount)
            .unwrap_or_revert_with(ApiError::Transfer);
    }
}
