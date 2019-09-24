#![no_std]

extern crate alloc;
extern crate contract_ffi;

use alloc::vec::Vec;
use contract_ffi::contract_api::{self, Error, TransferResult};
use contract_ffi::value::account::PublicKey;
use contract_ffi::value::U512;
use core::convert::TryFrom;

#[no_mangle]
pub extern "C" fn call() {
    let accounts: Vec<PublicKey> = {
        let data: Vec<Vec<u8>> = match contract_api::get_arg(0) {
            Some(Ok(data)) => data,
            Some(Err(_)) => contract_api::revert(Error::InvalidArgument.into()),
            None => contract_api::revert(Error::MissingArgument.into()),
        };
        data.into_iter()
            .map(|bytes| {
                PublicKey::try_from(bytes.as_slice())
                    .unwrap_or_else(|_| contract_api::revert(Error::Deserialize.into()))
            })
            .collect()
    };
    let seed_amount: U512 = match contract_api::get_arg(1) {
        Some(Ok(data)) => data,
        Some(Err(_)) => contract_api::revert(Error::InvalidArgument.into()),
        None => contract_api::revert(Error::MissingArgument.into()),
    };
    for public_key in accounts {
        let result = contract_ffi::contract_api::transfer_to_account(public_key, seed_amount);
        if result == TransferResult::TransferError {
            contract_api::revert(Error::Transfer.into());
        }
    }
}
