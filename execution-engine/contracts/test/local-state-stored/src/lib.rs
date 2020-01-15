#![no_std]

use contract::{
    contract_api::{runtime, storage},
    unwrap_or_revert::UnwrapOrRevert,
};
use types::ApiError;

const ENTRY_FUNCTION_NAME: &str = "delegate";
const CONTRACT_NAME: &str = "local_state_stored";

#[no_mangle]
pub extern "C" fn delegate() {
    local_state::delegate()
}

#[no_mangle]
pub extern "C" fn call() {
    let key = storage::store_function(ENTRY_FUNCTION_NAME, Default::default())
        .into_uref()
        .unwrap_or_revert_with(ApiError::UnexpectedContractRefVariant)
        .into();

    runtime::put_key(CONTRACT_NAME, key);
}
