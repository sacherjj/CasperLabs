#![no_std]
#![feature(cell_update)]

extern crate alloc;
extern crate contract_ffi;
extern crate local_state;

use alloc::collections::BTreeMap;
use alloc::string::String;
use contract_ffi::contract_api;
use contract_ffi::key::Key;

const ENTRY_FUNCTION_NAME: &str = "delegate";
const CONTRACT_NAME: &str = "local_state_stored";

#[no_mangle]
pub extern "C" fn delegate() {
    local_state::delegate()
}

#[no_mangle]
pub extern "C" fn call() {
    let known_urefs: BTreeMap<String, Key> = BTreeMap::new();
    let contract = contract_api::fn_by_name(ENTRY_FUNCTION_NAME, known_urefs);
    let key = contract_api::new_turef(contract).into();
    contract_api::add_uref(CONTRACT_NAME, &key);
}
