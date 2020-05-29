#![no_std]
#![no_main]

use contract::{contract_api::runtime, unwrap_or_revert::UnwrapOrRevert};
use types::{account::AccountHash, ApiError};

#[no_mangle]
pub extern "C" fn call() {
    let known_public_key: AccountHash = runtime::get_arg(0)
        .unwrap_or_revert_with(ApiError::MissingArgument)
        .unwrap_or_revert_with(ApiError::InvalidArgument);
    let caller_public_key: AccountHash = runtime::get_caller();
    assert_eq!(
        caller_public_key, known_public_key,
        "caller public key was not known public key"
    );
}
