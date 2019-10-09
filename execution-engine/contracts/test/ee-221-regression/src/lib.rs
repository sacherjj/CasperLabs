#![no_std]

extern crate contract_ffi;

use contract_ffi::contract_api;
use contract_ffi::key::Key;

#[no_mangle]
pub extern "C" fn call() {
    let res1 = contract_api::runtime::get_key("nonexistinguref");
    assert!(res1.is_none());

    let key = Key::URef(contract_api::storage::new_turef(()).into());
    contract_api::runtime::put_key("nonexistinguref", &key);

    let res2 = contract_api::runtime::get_key("nonexistinguref");

    assert_eq!(res2, Some(key));
}
