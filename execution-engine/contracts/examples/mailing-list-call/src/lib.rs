#![no_std]

extern crate alloc;

use alloc::{string::String, vec::Vec};
use core::convert::{From, TryInto};

use contract::{
    contract_api::{runtime, storage, TURef},
    unwrap_or_revert::UnwrapOrRevert,
};
use types::{ApiError, Key};

const MAIL_FEED_KEY: &str = "mail_feed";
const MAILING_KEY: &str = "mailing";
const PUB_METHOD: &str = "pub";
const SUB_METHOD: &str = "sub";

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
    let contract_key = runtime::get_key(MAILING_KEY).unwrap_or_revert_with(ApiError::GetKey);
    let contract_ref = contract_key
        .to_contract_ref()
        .unwrap_or_revert_with(ApiError::UnexpectedKeyVariant);

    let name = "CasperLabs";
    let args = (SUB_METHOD, name);
    let sub_key = runtime::call_contract::<_, Option<Key>>(contract_ref.clone(), args)
        .unwrap_or_revert_with(Error::NoSubKey);

    runtime::put_key(MAIL_FEED_KEY, sub_key);

    let key_name_uref =
        runtime::get_key(MAIL_FEED_KEY).unwrap_or_revert_with(Error::GetKeyNameURef);
    if sub_key != key_name_uref {
        runtime::revert(Error::BadSubKey);
    }

    let message = "Hello, World!";
    let args = (PUB_METHOD, message);
    runtime::call_contract::<_, ()>(contract_ref, args);

    let list_key: TURef<Vec<String>> = sub_key.try_into().unwrap_or_revert();
    let messages = storage::read(list_key)
        .unwrap_or_revert_with(Error::GetMessagesURef)
        .unwrap_or_revert_with(Error::FindMessagesURef);

    if messages.is_empty() {
        runtime::revert(Error::NoMessages);
    }
}
