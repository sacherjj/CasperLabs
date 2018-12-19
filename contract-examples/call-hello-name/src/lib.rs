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
        143, 56, 101, 95, 235, 118, 21, 248, 41, 250, 70, 39, 125, 255, 70, 118, 145, 30, 234, 52, 199, 14, 138, 250, 95, 83, 37, 135, 126, 43, 65, 239
    ]);
    let contract = read(&hash);
    let arg = String::from("World");
    let args = vec![arg.to_bytes()];
    let result: String = call_contract(&contract, &args);
    assert_eq!("Hello, World", result);
}
