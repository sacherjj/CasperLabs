#![no_std]

extern crate alloc;

use alloc::{collections::BTreeMap, string::String};

use contract::{
    contract_api::{runtime, storage},
    unwrap_or_revert::UnwrapOrRevert,
};
use types::{ApiError, CLValue};

const HELLO_NAME_EXT: &str = "hello_name_ext";
const HELLO_NAME_KEY: &str = "hello_name";

enum Arg {
    Name = 0,
}

fn hello_name(name: &str) -> String {
    let mut result = String::from("Hello, ");
    result.push_str(name);
    result
}

#[no_mangle]
pub extern "C" fn hello_name_ext() {
    let name: String = runtime::get_arg(Arg::Name as u32)
        .unwrap_or_revert_with(ApiError::MissingArgument)
        .unwrap_or_revert_with(ApiError::InvalidArgument);
    let return_value = CLValue::from_t(hello_name(&name)).unwrap_or_revert();
    runtime::ret(return_value);
}

#[no_mangle]
pub extern "C" fn call() {
    let pointer = storage::store_function_at_hash(HELLO_NAME_EXT, BTreeMap::new());
    runtime::put_key(HELLO_NAME_KEY, pointer.into());
}
