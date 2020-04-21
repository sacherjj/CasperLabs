#![no_std]
#![no_main]

extern crate alloc;

use alloc::string::String;

use contract::contract_api::{runtime, storage, system};
use types::Key;

const NEW_ENDPOINT_NAME: &str = "version";
const RESULT_UREF_NAME: &str = "output_version";

#[no_mangle]
pub extern "C" fn call() {
    let mint_pointer = system::get_mint();
    let value: String = runtime::call_contract(mint_pointer, (NEW_ENDPOINT_NAME,));
    let value_uref = storage::new_uref(value);
    let key = Key::URef(value_uref);
    runtime::put_key(RESULT_UREF_NAME, key);
}
