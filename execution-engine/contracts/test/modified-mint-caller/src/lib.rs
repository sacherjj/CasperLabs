#![no_std]

#[macro_use]
extern crate alloc;

extern crate contract_ffi;

use alloc::string::String;

use contract_ffi::contract_api;
use contract_ffi::key::Key;

const NEW_ENDPOINT_NAME: &str = "version";
const RESULT_TUREF_NAME: &str = "output_version";

#[no_mangle]
pub extern "C" fn call() {
    let mint_pointer = contract_api::get_mint();
    let value: String = contract_api::call_contract(mint_pointer, &(NEW_ENDPOINT_NAME,), &vec![]);
    let value_turef = contract_api::new_turef(value);
    let key = Key::URef(value_turef.into());
    contract_api::put_key(RESULT_TUREF_NAME, &key);
}
