#![no_std]

use contract::{
    contract_api::{account, runtime},
    unwrap_or_revert::UnwrapOrRevert,
};
use types::{
    account::{PublicKey, Weight},
    ApiError,
};

enum Arg {
    Account = 0,
    Weight = 1,
}

#[repr(u16)]
enum Error {
    UpdateAssociatedKey = 100,
}

impl Into<ApiError> for Error {
    fn into(self) -> ApiError {
        ApiError::User(self as u16)
    }
}

#[no_mangle]
pub extern "C" fn call() {
    let account: PublicKey = runtime::get_arg(Arg::Account as u32)
        .unwrap_or_revert_with(ApiError::MissingArgument)
        .unwrap_or_revert_with(ApiError::InvalidArgument);
    let weight_val: u32 = runtime::get_arg(Arg::Weight as u32)
        .unwrap_or_revert_with(ApiError::MissingArgument)
        .unwrap_or_revert_with(ApiError::InvalidArgument);
    let weight = Weight::new(weight_val as u8);

    account::update_associated_key(account, weight)
        .unwrap_or_revert_with(Error::UpdateAssociatedKey);
}
