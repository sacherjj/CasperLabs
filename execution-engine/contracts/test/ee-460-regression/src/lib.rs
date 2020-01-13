#![no_std]

use contract::{
    contract_api::{runtime, system, Error},
    unwrap_or_revert::UnwrapOrRevert,
    value::{account::PublicKey, U512},
};

#[no_mangle]
pub extern "C" fn call() {
    let amount: U512 = runtime::get_arg(0)
        .unwrap_or_revert_with(Error::MissingArgument)
        .unwrap_or_revert_with(Error::InvalidArgument);

    let public_key = PublicKey::new([42; 32]);
    let result = system::transfer_to_account(public_key, amount);
    assert_eq!(result, Err(Error::Transfer))
}
