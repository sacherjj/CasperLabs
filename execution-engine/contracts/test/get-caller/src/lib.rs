#![no_std]

use contract_ffi::{
    contract_api::{runtime, Error},
    unwrap_or_revert::UnwrapOrRevert,
    value::account::PublicKey,
};

#[no_mangle]
pub extern "C" fn call() {
    let known_public_key: PublicKey = runtime::get_arg(0)
        .unwrap_or_revert_with(Error::MissingArgument)
        .unwrap_or_revert_with(Error::InvalidArgument);
    let caller_public_key: PublicKey = runtime::get_caller();
    assert_eq!(
        caller_public_key, known_public_key,
        "caller public key was not known public key"
    );
}
