#![no_std]
#![feature(alloc)]

#[macro_use]
extern crate alloc;

extern crate common;
use common::bytesrepr::ToBytes;
use common::ext::call_contract;
use common::key::Key;

#[no_mangle]
pub extern "C" fn call() {
    //This hash comes from blake2b256( [0;32] ++ [0;8] ++ [0;4] )
    let hash = Key::Hash([
        94, 95, 50, 162, 218, 237, 110, 252, 109, 151, 87, 89, 218, 215, 97, 65, 124, 183, 21, 252,
        197, 6, 112, 204, 31, 83, 118, 122, 225, 214, 26, 52,
    ]);
    let arg = "inc";
    let args = vec![arg.to_bytes()];
    let _result: () = call_contract(&hash, &args);
    let value: i32 = {
        let arg = "get";
        let args = vec![arg.to_bytes()];
        call_contract(&hash, &args)
    };
    assert_eq!(value, 1);
}
