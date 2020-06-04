#![no_std]
#![no_main]

use contract::{contract_api::runtime, unwrap_or_revert::UnwrapOrRevert};
use types::{account::AccountHash, ApiError};

#[no_mangle]
pub extern "C" fn call() {
    let known_account_hash: AccountHash = runtime::get_arg(0)
        .unwrap_or_revert_with(ApiError::MissingArgument)
        .unwrap_or_revert_with(ApiError::InvalidArgument);
    let caller_account_hash: AccountHash = runtime::get_caller();
    assert_eq!(
        caller_account_hash, known_account_hash,
        "caller account hash was not known account hash"
    );
}
