#![no_std]

extern crate alloc;
extern crate contract_ffi;

use alloc::collections::btree_map::BTreeMap;
use alloc::string::String;

use contract_ffi::contract_api::{runtime, storage, ContractRef};

#[no_mangle]
pub extern "C" fn hello_ext() {
    let test_string = String::from("Hello, world!");
    let test_uref = storage::new_turef(test_string).into();
    let extra_urefs = [test_uref].to_vec();
    runtime::ret(test_uref, extra_urefs)
}

#[no_mangle]
pub extern "C" fn call() {
    let named_keys = BTreeMap::new();
    let contract_pointer: ContractRef = storage::store_function_at_hash("hello_ext", named_keys);
    runtime::put_key("hello_ext", &contract_pointer.into());
}
