#![no_std]
#![no_main]

use contract::{contract_api::runtime, unwrap_or_revert::UnwrapOrRevert};
use types::{contracts::DEFAULT_ENTRY_POINT_NAME, ApiError};

const LIST_NAMED_KEYS_KEY: &str = "list_named_keys";

#[no_mangle]
pub extern "C" fn call() {
    let list_named_keys_key =
        runtime::get_key(LIST_NAMED_KEYS_KEY).unwrap_or_revert_with(ApiError::GetKey);

    // Call `define` part of the contract.
    runtime::call_contract(list_named_keys_key, DEFAULT_ENTRY_POINT_NAME, ())
}
