#![no_std]
#![feature(alloc)]

#[macro_use]
extern crate alloc;
use alloc::string::String;
use alloc::vec::Vec;

extern crate common;
use common::contract_api::pointers::*;
use common::contract_api::*;
use common::key::Key;

#[no_mangle]
pub extern "C" fn call() {
    //This hash comes from blake2b256( [0;32] ++ [0;8] ++ [0;4] )
    let hash = ContractPointer::Hash([
        19, 139, 15, 184, 252, 148, 79, 127, 8, 247, 7, 237, 133,
        28, 224, 206, 228, 83, 133, 177, 214, 61, 225, 167, 164,
        83, 34, 159, 204, 21, 112, 65
    ]);
    let method = "sub";
    let name = "CasperLabs";
    let args = (method, name);
    let maybe_sub_key: Option<Key> = call_contract(hash.clone(), &args, &Vec::new());
    let sub_key = maybe_sub_key.unwrap();

    let key_name = "mail_feed";
    add_uref(key_name, &sub_key);
    assert_eq!(sub_key, get_uref(key_name));

    let method = "pub";
    let message = "Hello, World!";
    let args = (method, message);
    let _result: () = call_contract(hash, &args, &Vec::new());

    let list_key: UPointer<Vec<String>> = sub_key.to_u_ptr().unwrap();
    let messages = read(list_key);

    assert_eq!(
        vec![String::from("Welcome!"), String::from(message)],
        messages
    );
}
