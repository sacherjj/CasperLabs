#![no_std]

extern crate alloc;
extern crate contract_ffi;

use binascii::ConvertError;
use contract_ffi::contract_api;
use contract_ffi::contract_api::TransferResult;
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
        Err(_) => contract_api::revert(12),
    };
    let amount: U512 = U512::from(initial_amount);

    match contract_api::transfer_to_account(public_key, amount) {
        TransferResult::TransferredToNewAccount => (),
        TransferResult::TransferredToExistingAccount => contract_api::revert(10),
        TransferResult::TransferError => contract_api::revert(11),
    }
}
