#![no_std]

extern crate contract_ffi;

use contract_ffi::contract_api::{self, Error};
use contract_ffi::unwrap_or_revert::UnwrapOrRevert;
use contract_ffi::value::account::PublicKey;
use contract_ffi::value::U512;

#[no_mangle]
pub extern "C" fn call() {
    let amount: U512 = contract_api::runtime::get_arg(0)
        .unwrap_or_revert_with(Error::MissingArgument)
        .unwrap_or_revert_with(Error::InvalidArgument);

    let public_key = PublicKey::new([42; 32]);
    let result = contract_api::system::transfer_to_account(public_key, amount);
    assert_eq!(result, Err(Error::Transfer))
}
