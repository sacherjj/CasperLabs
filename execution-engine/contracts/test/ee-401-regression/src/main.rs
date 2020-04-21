#![no_std]
#![no_main]

extern crate alloc;

use alloc::{collections::BTreeMap, string::String};

use contract::{
    contract_api::{runtime, storage},
    unwrap_or_revert::UnwrapOrRevert,
};
use types::{CLValue, ContractRef, URef};

#[no_mangle]
pub extern "C" fn hello_ext() {
    let test_string = String::from("Hello, world!");
    let test_uref: URef = storage::new_uref(test_string);
    let return_value = CLValue::from_t(test_uref).unwrap_or_revert();
    runtime::ret(return_value)
}

#[no_mangle]
pub extern "C" fn call() {
    let named_keys = BTreeMap::new();
    let contract_pointer: ContractRef = storage::store_function_at_hash("hello_ext", named_keys);
    runtime::put_key("hello_ext", contract_pointer.into());
}
