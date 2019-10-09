#![no_std]

extern crate contract_ffi;

use contract_ffi::contract_api::{self, Error};
use contract_ffi::unwrap_or_revert::UnwrapOrRevert;
use contract_ffi::value::account::{PublicKey, Weight};

#[no_mangle]
pub extern "C" fn call() {
    let account: PublicKey = contract_api::get_arg(0)
        .unwrap_or_revert_with(Error::MissingArgument)
        .unwrap_or_revert_with(Error::InvalidArgument);
    let weight_val: u32 = contract_api::get_arg(1)
        .unwrap_or_revert_with(Error::MissingArgument)
        .unwrap_or_revert_with(Error::InvalidArgument);
    let weight = Weight::new(weight_val as u8);

    contract_api::add_associated_key(account, weight)
        .unwrap_or_else(|_| contract_api::revert(Error::User(100)));
}
