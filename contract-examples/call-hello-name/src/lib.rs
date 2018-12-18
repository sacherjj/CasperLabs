#![no_std]
#![feature(alloc)]

#[macro_use]
extern crate alloc;
use alloc::string::String;

extern crate common;
use common::bytesrepr::BytesRepr;
use common::ext::{call_contract, read};
use common::key::Key;

#[no_mangle]
pub extern "C" fn call() {
    //This hash comes from the `_hash` output from `store_hello_name/src/lib.rs`
    let hash = Key::Hash([
        252, 193, 165, 207, 104, 236, 69, 249, 43, 184, 46, 86, 93, 138, 54, 138, 233, 69, 41, 234,
        54, 211, 111, 36, 103, 231, 187, 227, 15, 72, 71, 111,
    ]);
    let contract = read(&hash);
    let arg = String::from("World");
    let args = vec![arg.to_bytes()];
    let result: String = call_contract(&contract, &args);
    assert_eq!("Hello, World", result);
}
