#![no_std]
#![no_main]

use contract::{
    contract_api::{account, runtime},
    unwrap_or_revert::UnwrapOrRevert,
};
use types::{account::AccountHash, ApiError};

#[no_mangle]
pub extern "C" fn call() {
    let account: AccountHash = runtime::get_arg(0)
        .unwrap_or_revert_with(ApiError::MissingArgument)
        .unwrap_or_revert_with(ApiError::InvalidArgument);
    account::remove_associated_key(account).unwrap_or_revert_with(ApiError::User(0))
}
