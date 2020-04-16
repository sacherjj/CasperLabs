#![no_std]
#![no_main]

use contract::{
    contract_api::{account, runtime},
    unwrap_or_revert::UnwrapOrRevert,
};
use types::{ApiError, URef};

#[no_mangle]
pub extern "C" fn call() {
    let known_main_purse: URef = runtime::get_arg(0)
        .unwrap_or_revert_with(ApiError::MissingArgument)
        .unwrap_or_revert_with(ApiError::InvalidArgument);
    let main_purse: URef = account::get_main_purse();
    assert_eq!(
        main_purse, known_main_purse,
        "main purse was not known purse"
    );
}
