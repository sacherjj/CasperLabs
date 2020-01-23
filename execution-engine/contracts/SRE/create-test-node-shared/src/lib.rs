#![no_std]

use base16;

use contract::{
    contract_api::{runtime, system},
    unwrap_or_revert::UnwrapOrRevert,
};
use types::{account::PublicKey, ApiError, TransferredTo, U512};

#[repr(u16)]
enum Error {
    AccountAlreadyExists = 10,
    TransferFailed = 11,
    FailedToParsePublicKey = 12,
}

impl Into<ApiError> for Error {
    fn into(self) -> ApiError {
        ApiError::User(self as u16)
    }
}

fn parse_public_key(hex: &[u8]) -> PublicKey {
    let mut buffer = [0u8; 32];
    let bytes_written = base16::decode_slice(hex, &mut buffer)
        .ok()
        .unwrap_or_revert_with(Error::FailedToParsePublicKey);
    if bytes_written != buffer.len() {
        runtime::revert(Error::FailedToParsePublicKey)
    }
    PublicKey::new(buffer)
}

pub fn create_account(account_addr: &[u8; 64], initial_amount: u64) {
    let public_key = parse_public_key(account_addr);
    let amount: U512 = U512::from(initial_amount);

    match system::transfer_to_account(public_key, amount)
        .unwrap_or_revert_with(Error::TransferFailed)
    {
        TransferredTo::NewAccount => (),
        TransferredTo::ExistingAccount => runtime::revert(Error::AccountAlreadyExists),
    }
}
