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
    GetKeyNameURef = 0,
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
    let contract_key = runtime::get_key("mailing").unwrap_or_revert_with(ApiError::GetKey);
    let pointer = match contract_key {
        Key::Hash(hash) => ContractRef::Hash(hash),
        _ => runtime::revert(ApiError::UnexpectedKeyVariant),
    };

    let method = "sub";
    let name = "CasperLabs";
    let args = (method, name);
    let maybe_sub_key: Option<Key> = runtime::call_contract(pointer.clone(), &args, &Vec::new());
    let sub_key = maybe_sub_key.unwrap_or_revert_with(Error::NoSubKey);

    let key_name = "mail_feed";
    runtime::put_key(key_name, &sub_key);

    let key_name_uref = runtime::get_key(key_name).unwrap_or_revert_with(Error::GetKeyNameURef);
    if sub_key != key_name_uref {
        runtime::revert(Error::BadSubKey);
    }

    let method = "pub";
    let message = "Hello, World!";
    let args = (method, message);
    runtime::call_contract::<_, ()>(pointer, &args, &Vec::new());

    let list_key: TURef<Vec<String>> = sub_key
        .to_turef()
        .unwrap_or_revert_with(ApiError::UnexpectedKeyVariant);
    let messages = storage::read(list_key)
        .unwrap_or_revert_with(Error::GetMessagesURef)
        .unwrap_or_revert_with(Error::FindMessagesURef);

    if messages.is_empty() {
        runtime::revert(Error::NoMessages);
    }
}
