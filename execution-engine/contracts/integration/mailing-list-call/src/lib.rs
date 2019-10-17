#![no_std]

extern crate alloc;
extern crate contract_ffi;

use alloc::string::String;
use alloc::vec::Vec;
use core::convert::From;

use contract_ffi::contract_api::{runtime, storage, Error as ApiError};
use contract_ffi::contract_api::{ContractRef, TURef};
use contract_ffi::key::Key;
use contract_ffi::unwrap_or_revert::UnwrapOrRevert;

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
    let mailing_uref = runtime::get_key("mailing").unwrap_or_revert_with(Error::GetMailingURef);
    let pointer = if let Key::Hash(hash) = mailing_uref {
        ContractRef::Hash(hash)
    } else {
        runtime::revert(Error::WrongURefType); // exit code is currently arbitrary
    };

    let method = "sub";
    let name = "CasperLabs";
    let args = (method, name);
    match runtime::call_contract(pointer.clone(), &args, &Vec::new()) {
        Some(sub_key) => {
            let key_name = "mail_feed";
            runtime::put_key(key_name, &sub_key);

            let key_name_uref =
                runtime::get_key(key_name).unwrap_or_revert_with(Error::GetKeyNameURef);
            if sub_key != key_name_uref {
                runtime::revert(Error::BadSubKey);
            }

            let method = "pub";
            let message = "Hello, World!";
            let args = (method, message);
            runtime::call_contract::<_, ()>(pointer, &args, &Vec::new());

            let turef: TURef<Vec<String>> = sub_key.to_turef().unwrap();
            let messages = storage::read(turef)
                .unwrap_or_revert_with(Error::GetMessagesURef)
                .unwrap_or_revert_with(Error::FindMessagesURef);

            if messages.is_empty() {
                runtime::revert(Error::NoMessages);
            }
        }
        None => {
            runtime::revert(Error::NoSubKey);
        }
    }
}
