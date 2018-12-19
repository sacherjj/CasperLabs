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
    //This hash comes from blake2b256( [0;32] ++ [0;8] ++ [0;4] )
    let hash = Key::Hash([
        94, 95, 50, 162, 218, 237, 110, 252, 109, 151, 87, 89, 218, 215, 97, 65, 124, 183, 21, 252,
        197, 6, 112, 204, 31, 83, 118, 122, 225, 214, 26, 52,
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
