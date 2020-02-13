#![no_std]

use contract::{
    contract_api::{runtime, storage, TURef},
    unwrap_or_revert::UnwrapOrRevert,
};
use types::{AccessRights, ApiError, URef};

const CONTRACT_UREF: u32 = 0;

#[repr(u16)]
enum Error {
    CreateTURef,
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

    let forged_reference: TURef<&str> = {
        let ret = URef::new(uref.addr(), AccessRights::READ_ADD_WRITE);
        TURef::from_uref(ret)
            .unwrap_or_else(|_| runtime::revert(ApiError::User(Error::CreateTURef as u16)))
    };

    storage::write(forged_reference, &REPLACEMENT_DATA)
}
