#![no_std]
#![no_main]

use contract::{contract_api::runtime, unwrap_or_revert::UnwrapOrRevert};
use types::ApiError;

const GET_CALLER_KEY: &str = "get_caller";

#[no_mangle]
pub extern "C" fn call() {
    let get_caller_key = runtime::get_key(GET_CALLER_KEY).unwrap_or_revert_with(ApiError::GetKey);
    // Call `define` part of the contract.
    runtime::call_contract(get_caller_key, ())
}
