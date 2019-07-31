#![no_std]
#![feature(alloc)]

extern crate alloc;
use alloc::string::String;
use alloc::vec::Vec;

extern crate common;
use common::contract_api::pointers::*;
use common::contract_api::*;
use common::key::Key;

#[no_mangle]
pub extern "C" fn call() {
    let mailing_uref = get_uref("mailing").unwrap_or_else(|| revert(100));
    let pointer = if let Key::Hash(hash) = mailing_uref {
        ContractPointer::Hash(hash)
    } else {
        revert(66); // exit code is currently arbitrary
    };

    let method = "sub";
    let name = "CasperLabs";
    let args = (method, name);
    match call_contract(pointer.clone(), &args, &Vec::new()){
        Some(sub_key) => {
            let key_name = "mail_feed";
            add_uref(key_name, &sub_key);

            let key_name_uref = get_uref(key_name).unwrap_or_else(|| revert(101));
            if sub_key != key_name_uref {
                revert(1);
            }

            let method = "pub";
            let message = "Hello, World!";
            let args = (method, message);
            let _result: () = call_contract(pointer, &args, &Vec::new());

            let list_key: UPointer<Vec<String>> = sub_key.to_u_ptr().unwrap();
            let messages = read(list_key);

            if  messages.is_empty(){
                revert(2);
            }
        },
        None=>{
            revert(3);
        }
    }
}
