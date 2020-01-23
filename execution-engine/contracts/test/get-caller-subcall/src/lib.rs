#![no_std]

extern crate alloc;

use alloc::collections::BTreeMap;

use contract::{
    contract_api::{runtime, storage},
    unwrap_or_revert::UnwrapOrRevert,
};
use types::{account::PublicKey, ApiError, CLValue};

#[no_mangle]
pub extern "C" fn check_caller_ext() {
    let caller_public_key: PublicKey = runtime::get_caller();
    let return_value = CLValue::from_t(caller_public_key).unwrap_or_revert();
    runtime::ret(return_value)
}

#[no_mangle]
pub extern "C" fn call() {
    let known_public_key: PublicKey = runtime::get_arg(0)
        .unwrap_or_revert_with(ApiError::MissingArgument)
        .unwrap_or_revert_with(ApiError::InvalidArgument);
    let caller_public_key: PublicKey = runtime::get_caller();
    assert_eq!(
        caller_public_key, known_public_key,
        "caller public key was not known public key"
    );

    let pointer = storage::store_function_at_hash("check_caller_ext", BTreeMap::new());
    let subcall_public_key: PublicKey = runtime::call_contract(pointer, ());
    assert_eq!(
        subcall_public_key, known_public_key,
        "subcall public key was not known public key"
    );
}
