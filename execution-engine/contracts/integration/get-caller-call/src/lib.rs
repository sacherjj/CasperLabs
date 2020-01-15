#![no_std]

use contract::{contract_api::runtime, unwrap_or_revert::UnwrapOrRevert};
use types::{ApiError, ContractRef, Key};

const GET_CALLER_KEY: &str = "get_caller";

#[no_mangle]
pub extern "C" fn call() {
    let get_caller_uref = runtime::get_key(GET_CALLER_KEY).unwrap_or_revert_with(ApiError::GetKey);
    let contract_ref = match get_caller_uref {
        Key::Hash(hash) => ContractRef::Hash(hash),
        _ => runtime::revert(ApiError::UnexpectedKeyVariant),
    };

    // Call `define` part of the contract.
    runtime::call_contract(contract_ref, ())
}
