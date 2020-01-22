#![no_std]

use contract::{contract_api::runtime, unwrap_or_revert::UnwrapOrRevert};
use types::ApiError;

enum Arg {
    Number = 0,
}

#[no_mangle]
pub extern "C" fn call() {
    let number: u32 = runtime::get_arg(Arg::Number as u32)
        .unwrap_or_revert_with(ApiError::MissingArgument)
        .unwrap_or_revert_with(ApiError::InvalidArgument);
    runtime::revert(ApiError::User(number as u16));
}
