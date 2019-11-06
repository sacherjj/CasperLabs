#![no_std]

use binascii::ConvertError;

use contract_ffi::contract_api::system::TransferredTo;
use contract_ffi::contract_api::{runtime, system, Error};
use contract_ffi::unwrap_or_revert::UnwrapOrRevert;
use contract_ffi::value::account::PublicKey;
use contract_ffi::value::uint::U512;

fn parse_public_key(hex: &[u8]) -> Result<PublicKey, ConvertError> {
    let mut buff = [0u8; 32];
    binascii::hex2bin(hex, &mut buff)?;
    Ok(PublicKey::new(buff))
}

pub fn create_account(account_addr: &[u8; 64], initial_amount: u64) {
    let public_key: PublicKey = match parse_public_key(account_addr) {
        Ok(public_key) => public_key,
        Err(_) => runtime::revert(Error::User(12)),
    };
    let amount: U512 = U512::from(initial_amount);

    match system::transfer_to_account(public_key, amount).unwrap_or_revert_with(Error::User(11)) {
        TransferredTo::NewAccount => (),
        TransferredTo::ExistingAccount => runtime::revert(Error::User(10)),
    }
}
