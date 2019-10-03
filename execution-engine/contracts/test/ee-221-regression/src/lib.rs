#![no_std]
#![feature(cell_update)]

extern crate alloc;

extern crate contract_ffi;

use contract_ffi::contract_api::{get_key, new_turef, put_key};
use contract_ffi::key::Key;

#[no_mangle]
pub extern "C" fn call() {
    let res1 = get_key("nonexistinguref");
    assert!(res1.is_none());

    let key = Key::URef(new_turef(()).into());
    put_key("nonexistinguref", &key);

    let res2 = get_key("nonexistinguref");

    assert_eq!(res2, Some(key));
}
