#![no_std]

extern crate alloc;

use alloc::string::String;
use alloc::vec::Vec;

use contract_ffi::contract_api::{runtime, storage, Error};
use contract_ffi::unwrap_or_revert::UnwrapOrRevert;
use contract_ffi::value::Value;

const HELLO_NAME_KEY: &str = "hello_name";
const HELLOWORLD_KEY: &str = "helloworld";

#[no_mangle]
pub extern "C" fn call() {
    let contract_key = runtime::get_key(HELLO_NAME_KEY).unwrap_or_revert_with(Error::GetKey);
    let contract_ref = contract_key
        .to_c_ptr()
        .unwrap_or_revert_with(Error::UnexpectedKeyVariant);

    let args = ("World",);
    let result: String = runtime::call_contract(contract_ref, &args, &Vec::new());
    assert_eq!("Hello, World", result);

    // Store the result at a uref so it can be seen as an effect on the global state
    runtime::put_key(
        HELLOWORLD_KEY,
        &storage::new_turef(Value::String(result)).into(),
    );
}
