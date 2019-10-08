#![no_std]

extern crate alloc;

use alloc::collections::BTreeMap;
use alloc::string::String;
use alloc::vec::Vec;

extern crate contract_ffi;

use contract_ffi::contract_api::{get_arg, put_key, ret, store_function_at_hash};

fn hello_name(name: &str) -> String {
    let mut result = String::from("Hello, ");
    result.push_str(name);
    result
}

#[no_mangle]
pub extern "C" fn hello_name_ext() {
    let name: String = get_arg(0).unwrap().unwrap();
    let y = hello_name(&name);
    ret(&y, &Vec::new());
}

#[no_mangle]
pub extern "C" fn call() {
    let pointer = store_function_at_hash("hello_name_ext", BTreeMap::new());
    put_key("hello_name", &pointer.into());
}
