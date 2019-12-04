#![no_std]

use contract_ffi::{
    contract_api::{account, runtime, Error as ApiError},
    unwrap_or_revert::UnwrapOrRevert,
    value::account::{PublicKey, Weight},
};

enum Arg {
    Account = 0,
    Weight,
}

#[repr(u16)]
enum Error {
    AddAssociatedKey = 100,
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

    account::add_associated_key(account, weight).unwrap_or_revert_with(Error::AddAssociatedKey);
}
