#![no_std]
#![feature(alloc)]

#[macro_use]
extern crate alloc;
use alloc::string::String;

extern crate common;
use common::bytesrepr::ToBytes;
use common::ext::*;
use common::key::Key;
use common::value::Value;

#[no_mangle]
pub extern "C" fn call() {
    //This hash comes from blake2b256( [0;32] ++ [0;8] ++ [0;4] )
    let hash = Key::Hash([
        94, 95, 50, 162, 218, 237, 110, 252, 109, 151, 87, 89, 218, 215, 97, 65, 124, 183, 21, 252,
        197, 6, 112, 204, 31, 83, 118, 122, 225, 214, 26, 52,
    ]);
    let method = "sub";
    let name = "CasperLabs";
    let args = vec![method.to_bytes(), name.to_bytes()];
    let maybe_sub_key: Option<Key> = call_contract(&hash, &args);
    let sub_key = maybe_sub_key.unwrap();

    let key_name = "mail_feed";
    add_uref(key_name, &sub_key);
    assert_eq!(sub_key, get_uref(key_name));

    let method = "pub";
    let message = "Hello, World!";
    let args = vec![method.to_bytes(), message.to_bytes()];
    let _result: () = call_contract(&hash, &args);

    let messages = read(&sub_key);

    assert_eq!(
        Value::ListString(vec![String::from("Welcome!"), String::from(message)]),
        messages
    );
}
