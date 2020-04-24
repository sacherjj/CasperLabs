#![no_std]
#![no_main]

extern crate alloc;

use alloc::{string::String, vec::Vec};
use core::convert::{From, TryInto};

use contract::{
    contract_api::{runtime, storage},
    unwrap_or_revert::UnwrapOrRevert,
};
use types::{runtime_args, ApiError, Key, RuntimeArgs, SemVer, URef};

const MAIL_FEED_KEY: &str = "mail_feed";
const MAILING_KEY: &str = "mailing";
const PUB_METHOD: &str = "pub";
const SUB_METHOD: &str = "sub";
const MAILING_LIST_EXT: &str = "mailing_list_ext";

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
    let args = runtime_args! {
        "method_name" => SUB_METHOD,
        "arg1" => name,
    };
    let sub_key = runtime::call_versioned_contract::<Option<Key>>(
        contract_ref.clone(),
        SemVer::V1_0_0,
        MAILING_LIST_EXT,
        args,
    )
    .unwrap_or_revert_with(Error::NoSubKey);

    runtime::put_key(MAIL_FEED_KEY, sub_key);

    let key_name_uref =
        runtime::get_key(MAIL_FEED_KEY).unwrap_or_revert_with(Error::GetKeyNameURef);
    if sub_key != key_name_uref {
        runtime::revert(Error::BadSubKey);
    }

    let message = "Hello, World!";
    let args = runtime_args! {
        "method_name" => PUB_METHOD,
        "arg1" => message,
    };
    runtime::call_versioned_contract::<()>(contract_ref, SemVer::V1_0_0, MAILING_LIST_EXT, args);

    let list_uref: URef = sub_key.try_into().unwrap_or_revert();
    let messages: Vec<String> = storage::read(list_uref)
        .unwrap_or_revert_with(Error::GetMessagesURef)
        .unwrap_or_revert_with(Error::FindMessagesURef);

    if messages.is_empty() {
        runtime::revert(Error::NoMessages);
    }
}
