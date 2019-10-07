#![no_std]
#![feature(cell_update)]

extern crate alloc;
extern crate contract_ffi;

use contract_ffi::contract_api::{self, Error as ApiError};
use contract_ffi::value::account::{PublicKey, Weight};

const INIT_WEIGHT: u8 = 1;
const MOD_WEIGHT: u8 = 2;

#[repr(u16)]
enum Error {
    AddAssociatedKey,
    UpdateAssociatedKey,
}

#[no_mangle]
pub extern "C" fn call() {
    let account: PublicKey = match contract_api::get_arg(0) {
        Some(Ok(data)) => data,
        Some(Err(_)) => contract_api::revert(ApiError::InvalidArgument),
        None => contract_api::revert(ApiError::MissingArgument),
    };

    let weight1 = Weight::new(INIT_WEIGHT);
    contract_api::add_associated_key(account, weight1)
        .unwrap_or_else(|_| contract_api::revert(ApiError::User(Error::AddAssociatedKey as u16)));

    let weight2 = Weight::new(MOD_WEIGHT);
    contract_api::update_associated_key(account, weight2).unwrap_or_else(|_| {
        contract_api::revert(ApiError::User(Error::UpdateAssociatedKey as u16))
    });
}
