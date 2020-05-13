#![no_std]
#![no_main]

use contract::{
    contract_api::{runtime, storage},
    unwrap_or_revert::UnwrapOrRevert,
};
use types::{contracts::DEFAULT_ENTRY_POINT_NAME, AccessRights, ApiError, Key, URef};

const CONTRACT_POINTER: u32 = 0;
const REPLACEMENT_DATA: &str = "bawitdaba";

#[no_mangle]
pub extern "C" fn call() {
    let contract_key: Key = runtime::get_arg(CONTRACT_POINTER)
        .unwrap_or_revert_with(ApiError::MissingArgument)
        .unwrap_or_revert_with(ApiError::InvalidArgument);

    let reference: URef = runtime::call_contract(contract_key, DEFAULT_ENTRY_POINT_NAME, ());
    let forged_reference: URef = URef::new(reference.addr(), AccessRights::READ_ADD_WRITE);
    storage::write(forged_reference, REPLACEMENT_DATA)
}
