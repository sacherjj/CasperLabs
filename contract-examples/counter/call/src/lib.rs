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
    //This hash comes from the `_hash` output from `counter/define/src/lib.rs`
    let hash = Key::Hash([
        37, 87, 142, 123, 78, 18, 45, 31, 105, 3, 20, 28, 46, 183, 76, 54, 138, 85, 222, 160, 68,
        223, 250, 102, 192, 24, 98, 154, 222, 75, 239, 72,
    ]);
    let contract = read(&hash);
    let arg = String::from("inc").to_bytes();
    let args = vec![arg];
    let _result: () = call_contract(&contract, &args);
    let value: i32 = {
        let arg = String::from("get").to_bytes();
        let args = vec![arg];
        call_contract(&contract, &args)
    };
    assert_eq!(value, 1);
}
