#![no_std]

extern crate alloc;

use alloc::string::String;
use alloc::vec::Vec;

extern crate contract_ffi;

use contract_ffi::contract_api::pointers::*;
use contract_ffi::contract_api::*;
use contract_ffi::key::Key;

enum Error {
    GetMailingURef = 1001,
    WrongURefType = 1002,
    GetKeyNameURef = 1003,
    BadSubKey = 1004,
    GetMessagesURef = 1005,
    FindMessagesURef = 1006,
    NoMessages = 1007,
    NoSubKey = 1008,
}

#[no_mangle]
pub extern "C" fn call() {
    let mailing_uref = get_uref("mailing").unwrap_or_else(|| revert(Error::GetMailingURef as u32));
    let pointer = if let Key::Hash(hash) = mailing_uref {
        ContractPointer::Hash(hash)
    } else {
        revert(Error::WrongURefType as u32); // exit code is currently arbitrary
    };

    let method = "sub";
    let name = "CasperLabs";
    let args = (method, name);
    match call_contract(pointer.clone(), &args, &Vec::new()) {
        Some(sub_key) => {
            let key_name = "mail_feed";
            add_uref(key_name, &sub_key);

            let key_name_uref =
                get_uref(key_name).unwrap_or_else(|| revert(Error::GetKeyNameURef as u32));
            if sub_key != key_name_uref {
                revert(Error::BadSubKey as u32);
            }

            let method = "pub";
            let message = "Hello, World!";
            let args = (method, message);
            let _result: () = call_contract(pointer, &args, &Vec::new());

            let turef: TURef<Vec<String>> = sub_key.to_turef().unwrap();
            let messages = read(turef)
                .unwrap_or_else(|_| revert(Error::GetMessagesURef as u32))
                .unwrap_or_else(|| revert(Error::FindMessagesURef as u32));

            if messages.is_empty() {
                revert(Error::NoMessages as u32);
            }
        }
        None => {
            revert(Error::NoSubKey as u32);
        }
    }
}
