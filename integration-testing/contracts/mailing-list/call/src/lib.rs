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
        67, 232, 122, 169, 14, 106, 54, 34, 212, 177, 47, 78, 119, 93, 170, 83, 253, 198, 239, 179,
        90, 172, 18, 205, 87, 44, 42, 226, 77, 179, 219, 69,
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
