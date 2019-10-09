#![no_std]

extern crate contract_ffi;

use contract_ffi::contract_api::{self, Error};
use contract_ffi::unwrap_or_revert::UnwrapOrRevert;
use contract_ffi::value::account::PublicKey;
use contract_ffi::value::U512;

/// Executes mote transfer to supplied public key.
/// Transfers the requested amount.
#[no_mangle]
pub extern "C" fn call() {
    let public_key: PublicKey = contract_api::runtime::get_arg(0)
        .unwrap_or_revert_with(Error::MissingArgument)
        .unwrap_or_revert_with(Error::InvalidArgument);
    let transfer_amount: u64 = contract_api::runtime::get_arg(1)
        .unwrap_or_revert_with(Error::MissingArgument)
        .unwrap_or_revert_with(Error::InvalidArgument);
    let u512_motes = U512::from(transfer_amount);
    contract_api::system::transfer_to_account(public_key, u512_motes).unwrap_or_revert();
}
