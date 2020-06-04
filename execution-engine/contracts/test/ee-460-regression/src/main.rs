#![no_std]
#![no_main]

use contract::{
    contract_api::{runtime, system},
    unwrap_or_revert::UnwrapOrRevert,
};
use types::{account::AccountHash, ApiError, U512};

#[no_mangle]
pub extern "C" fn call() {
    let amount: U512 = runtime::get_arg(0)
        .unwrap_or_revert_with(ApiError::MissingArgument)
        .unwrap_or_revert_with(ApiError::InvalidArgument);

    let account_hash = AccountHash::new([42; 32]);
    let result = system::transfer_to_account(account_hash, amount);
    assert_eq!(result, Err(ApiError::Transfer))
}
