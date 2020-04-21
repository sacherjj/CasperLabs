#![no_std]
#![no_main]

use contract::{
    contract_api::{runtime, storage},
    unwrap_or_revert::UnwrapOrRevert,
};
use types::{AccessRights, ApiError, URef};

const CONTRACT_UREF: u32 = 0;

#[repr(u16)]
enum Error {
    InvalidURefArg,
}

const REPLACEMENT_DATA: &str = "bawitdaba";

#[no_mangle]
pub extern "C" fn call() {
    let uref: URef = runtime::get_arg(CONTRACT_UREF)
        .unwrap_or_revert_with(ApiError::MissingArgument)
        .unwrap_or_revert_with(ApiError::InvalidArgument);

    let is_valid = runtime::is_valid_uref(uref);
    if !is_valid {
        runtime::revert(ApiError::User(Error::InvalidURefArg as u16))
    }

    let forged_reference: URef = URef::new(uref.addr(), AccessRights::READ_ADD_WRITE);

    storage::write(forged_reference, REPLACEMENT_DATA)
}
