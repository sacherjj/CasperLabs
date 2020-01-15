#![no_std]

extern crate alloc;

use alloc::{collections::BTreeMap, string::String};

use contract::{
    contract_api::{runtime, storage},
    unwrap_or_revert::UnwrapOrRevert,
};
use types::{ApiError, Key};

const CONTRACT_NAME: &str = "do_nothing_stored";
const DESTINATION_HASH: &str = "hash";
const DESTINATION_UREF: &str = "uref";
const ENTRY_FUNCTION_NAME: &str = "delegate";

#[repr(u16)]
enum Error {
    UnknownDestination = 1,
}

impl Into<ApiError> for Error {
    fn into(self) -> ApiError {
        ApiError::User(self as u16)
    }
}

enum Arg {
    Destination = 0,
}

#[no_mangle]
pub extern "C" fn delegate() {}

fn store_at_hash() -> Key {
    let named_keys = BTreeMap::new();
    let pointer = storage::store_function_at_hash(ENTRY_FUNCTION_NAME, named_keys);
    pointer.into()
}

fn store_at_uref() -> Key {
    storage::store_function(ENTRY_FUNCTION_NAME, BTreeMap::new())
        .into_uref()
        .unwrap_or_revert_with(ApiError::UnexpectedContractRefVariant)
        .into()
}

#[no_mangle]
pub extern "C" fn call() {
    let destination: String = runtime::get_arg(Arg::Destination as u32)
        .unwrap_or_revert_with(ApiError::MissingArgument)
        .unwrap_or_revert_with(ApiError::InvalidArgument);

    let key = match destination.as_str() {
        DESTINATION_HASH => store_at_hash(),
        DESTINATION_UREF => store_at_uref(),
        _ => runtime::revert(Error::UnknownDestination),
    };
    runtime::put_key(CONTRACT_NAME, key);
}
