#![no_std]

extern crate contract_ffi;

use contract_ffi::contract_api::{account, runtime, Error};
use contract_ffi::unwrap_or_revert::UnwrapOrRevert;
use contract_ffi::value::account::{PublicKey, Weight};

#[no_mangle]
pub extern "C" fn call() {
    let account: PublicKey = runtime::get_arg(0)
        .unwrap_or_revert_with(Error::MissingArgument)
        .unwrap_or_revert_with(Error::InvalidArgument);
    let weight_val: u32 = runtime::get_arg(1)
        .unwrap_or_revert_with(Error::MissingArgument)
        .unwrap_or_revert_with(Error::InvalidArgument);
    let weight = Weight::new(weight_val as u8);

    account::update_associated_key(account, weight)
        .unwrap_or_else(|_| runtime::revert(Error::User(100)));
}
