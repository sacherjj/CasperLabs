#![no_std]

use contract_ffi::{
    contract_api::{runtime, system, Error},
    unwrap_or_revert::UnwrapOrRevert,
    value::{account::PublicKey, U512},
};

const ACCOUNT_2_ADDR: [u8; 32] = [2u8; 32];

#[no_mangle]
pub extern "C" fn call() {
    let public_key = PublicKey::new(ACCOUNT_2_ADDR);
    let amount: U512 = runtime::get_arg(0)
        .unwrap_or_revert_with(Error::MissingArgument)
        .unwrap_or_revert_with(Error::InvalidArgument);

    let result = system::transfer_to_account(public_key, amount);
    assert!(result.is_ok());
}
