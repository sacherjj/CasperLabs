#![no_std]
#![no_main]

use core::convert::Into;

use contract::{
    contract_api::{runtime, storage},
    unwrap_or_revert::UnwrapOrRevert,
};
use types::{AccessRights, CLValue, Key, URef};

const DATA: &str = "data";
const CONTRACT_NAME: &str = "create";

#[no_mangle]
pub extern "C" fn create() {
    let reference: URef = storage::new_uref(DATA);
    let read_only_reference: URef = URef::new(reference.addr(), AccessRights::READ);
    let return_value = CLValue::from_t(read_only_reference).unwrap_or_revert();
    runtime::ret(return_value)
}

#[no_mangle]
pub extern "C" fn call() {
    let contract: Key = storage::store_function_at_hash(CONTRACT_NAME, Default::default()).into();
    runtime::put_key(CONTRACT_NAME, contract)
}
