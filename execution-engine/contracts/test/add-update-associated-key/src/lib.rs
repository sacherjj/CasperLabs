#![no_std]

extern crate contract_ffi;

use contract_ffi::contract_api::{account, runtime, Error as ApiError};
use contract_ffi::unwrap_or_revert::UnwrapOrRevert;
use contract_ffi::value::account::{PublicKey, Weight};

const INIT_WEIGHT: u8 = 1;
const MOD_WEIGHT: u8 = 2;

#[no_mangle]
pub extern "C" fn call() {
    let account: PublicKey = runtime::get_arg(0)
        .unwrap_or_revert_with(ApiError::MissingArgument)
        .unwrap_or_revert_with(ApiError::InvalidArgument);

    let weight1 = Weight::new(INIT_WEIGHT);
    account::add_associated_key(account, weight1).unwrap_or_revert();

    let weight2 = Weight::new(MOD_WEIGHT);
    account::update_associated_key(account, weight2).unwrap_or_revert();
}
