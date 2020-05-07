#![no_std]
#![no_main]

extern crate alloc;

use alloc::{collections::BTreeMap, string::ToString};

use contract::contract_api::storage;
use types::contract_header::EntryPoint;

const ENTRY_FUNCTION_NAME: &str = "delegate";
const HASH_KEY_NAME: &str = "do_nothing_hash";
const ACCESS_KEY_NAME: &str = "do_nothing_access";

#[no_mangle]
pub extern "C" fn delegate() {}

#[no_mangle]
pub extern "C" fn call() {
    let methods = {
        let mut methods = BTreeMap::new();
        methods.insert(ENTRY_FUNCTION_NAME.to_string(), EntryPoint::thunk());
        methods
    };

    storage::new_contract(
        methods,
        None,
        Some(HASH_KEY_NAME.to_string()),
        Some(ACCESS_KEY_NAME.to_string()),
    );
}
