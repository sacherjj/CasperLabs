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
        187, 226, 210, 121, 245, 216, 155, 188, 100, 117, 133, 27, 63, 146, 157, 223, 254, 187,
        102, 99, 175, 80, 253, 198, 60, 78, 36, 210, 171, 253, 219, 162,
    ]);
    let contract = read(&hash);
    let arg = String::from("inc").to_bytes();
    let args = vec![arg];
    let _result: () = call_contract(&contract, &args);
}
