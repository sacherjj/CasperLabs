#![no_std]

extern crate alloc;
extern crate contract_ffi;

use alloc::string::String;
use alloc::vec::Vec;
use core::convert::From;

use contract_ffi::contract_api::pointers::*;
use contract_ffi::contract_api::*;
use contract_ffi::contract_api::Error as ApiError;
use contract_ffi::key::Key;

#[repr(u16)]
enum Error {
    GetMailingURef = 0,
    WrongURefType,
    GetKeyNameURef,
    BadSubKey,
    GetMessagesURef,
    FindMessagesURef,
    NoMessages,
    NoSubKey,
}

impl From<Error> for ApiError {
    fn from(error: Error) -> Self {
        ApiError::User(error as u16)
    }
}

#[no_mangle]
pub extern "C" fn call() {
    let mailing_uref = get_uref("mailing").unwrap_or_else(|| revert(ApiError::from(Error::GetMailingURef).into()));
    let pointer = if let Key::Hash(hash) = mailing_uref {
        ContractPointer::Hash(hash)
    } else {
        revert(ApiError::from(Error::WrongURefType).into()); // exit code is currently arbitrary
    };

    let method = "sub";
    let name = "CasperLabs";
    let args = (method, name);
    match call_contract(pointer.clone(), &args, &Vec::new()) {
        Some(sub_key) => {
            let key_name = "mail_feed";
            add_uref(key_name, &sub_key);

            let key_name_uref =
                get_uref(key_name).unwrap_or_else(|| revert(ApiError::from(Error::GetKeyNameURef).into()));
            if sub_key != key_name_uref {
                revert(ApiError::from(Error::BadSubKey).into());
            }

            let method = "pub";
            let message = "Hello, World!";
            let args = (method, message);
            let _result: () = call_contract(pointer, &args, &Vec::new());

            let turef: TURef<Vec<String>> = sub_key.to_turef().unwrap();
            let messages = read(turef)
                .unwrap_or_else(|_| revert(ApiError::from(Error::GetMessagesURef).into()))
                .unwrap_or_else(|| revert(ApiError::from(Error::FindMessagesURef).into()));

            if messages.is_empty() {
                revert(ApiError::from(Error::NoMessages).into());
            }
        }
        None => {
            revert(ApiError::from(Error::NoSubKey).into());
        }
    }
}
