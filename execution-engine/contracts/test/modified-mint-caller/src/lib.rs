#![no_std]

extern crate alloc;

use alloc::string::String;

use contract::contract_api::{runtime, storage, system};
use types::Key;

const NEW_ENDPOINT_NAME: &str = "version";
const RESULT_TUREF_NAME: &str = "output_version";

#[no_mangle]
pub extern "C" fn call() {
    let mint_pointer = system::get_mint();
    let value: String = runtime::call_contract(mint_pointer, (NEW_ENDPOINT_NAME,));
    let value_turef = storage::new_turef(value);
    let key = Key::URef(value_turef.into());
    runtime::put_key(RESULT_TUREF_NAME, key);
}
