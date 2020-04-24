#![no_std]
#![no_main]

extern crate alloc;

// Can be removed once https://github.com/rust-lang/rustfmt/issues/3362 is resolved.
#[rustfmt::skip]
use alloc::vec;
use alloc::{
    boxed::Box,
    collections::BTreeMap,
    string::{String, ToString},
    vec::Vec,
};
use core::convert::TryInto;

use contract::{
    contract_api::{runtime, storage},
    unwrap_or_revert::UnwrapOrRevert,
};
use types::{
    ApiError, Arg, CLType, CLValue, EntryPoint, EntryPointAccess, EntryPointType, Key, SemVer, URef,
};

const LIST_KEY: &str = "list";
const MAILING_KEY: &str = "mailing";
const MAILING_LIST_EXT: &str = "mailing_list_ext";

const ARG_METHOD_NAME: &str = "method_name";
const ARG_ARG1: &str = "arg1";

#[repr(u16)]
enum Error {
    UnknownMethodName = 0,
}

impl Into<ApiError> for Error {
    fn into(self) -> ApiError {
        ApiError::User(self as u16)
    }
}

fn get_list_key(name: &str) -> URef {
    let key = runtime::get_key(name).unwrap_or_revert_with(ApiError::GetKey);
    key.try_into().unwrap_or_revert()
}

fn update_list(name: String) {
    let list_key = get_list_key(LIST_KEY);
    let mut list: Vec<String> = storage::read_or_revert(list_key);
    list.push(name);
    storage::write(list_key, list);
}

fn sub(name: String) -> Option<URef> {
    if runtime::has_key(&name) {
        let init_message = vec![String::from("Hello again!")];
        Some(storage::new_uref(init_message))
    } else {
        let init_message = vec![String::from("Welcome!")];
        let new_key = storage::new_uref(init_message);
        runtime::put_key(&name, new_key.clone().into());
        update_list(name);
        Some(new_key)
    }
}

fn publish(msg: String) {
    let curr_list: Vec<String> = storage::read_or_revert(get_list_key(LIST_KEY));
    for name in curr_list.iter() {
        let uref: URef = get_list_key(name);
        let mut messages: Vec<String> = storage::read_or_revert(uref);
        messages.push(msg.clone());
        storage::write(uref, messages);
    }
}

#[no_mangle]
pub extern "C" fn mailing_list_ext() {
    let method_name: String = runtime::get_named_arg(ARG_METHOD_NAME)
        .unwrap_or_revert_with(ApiError::MissingArgument)
        .unwrap_or_revert_with(ApiError::InvalidArgument);
    let arg1: String = runtime::get_named_arg(ARG_ARG1)
        .unwrap_or_revert_with(ApiError::MissingArgument)
        .unwrap_or_revert_with(ApiError::InvalidArgument);
    match method_name.as_str() {
        "sub" => match sub(arg1) {
            Some(uref) => {
                let return_value = CLValue::from_t(Some(Key::from(uref))).unwrap_or_revert();
                runtime::ret(return_value);
            }
            _ => {
                let return_value = CLValue::from_t(Option::<Key>::None).unwrap_or_revert();
                runtime::ret(return_value)
            }
        },
        // Note that this is totally insecure. In reality
        // the pub method would be only available under an
        // unforgable reference because otherwise anyone could
        // spam the mailing list.
        "pub" => publish(arg1),
        _ => runtime::revert(Error::UnknownMethodName),
    }
}

#[no_mangle]
pub extern "C" fn call() {
    let init_list: Vec<String> = Vec::new();
    let list_key = storage::new_uref(init_list);

    let (metadata_hash, access_uref) = storage::create_contract_metadata_at_hash();
    runtime::put_key(MAILING_KEY, metadata_hash);

    let methods = {
        let mut methods = BTreeMap::new();
        let entrypoint_hello = EntryPoint::new(
            vec![
                Arg::new(ARG_METHOD_NAME, CLType::String),
                Arg::new(ARG_ARG1, CLType::String),
            ],
            CLType::Option(Box::new(CLType::Key)),
            EntryPointAccess::Public,
            EntryPointType::Session,
        );
        methods.insert(MAILING_LIST_EXT.to_string(), entrypoint_hello);
        methods
    };

    // Create map of references for stored contract
    let mut mailing_list_urefs: BTreeMap<String, Key> = BTreeMap::new();
    let key_name = String::from(LIST_KEY);
    mailing_list_urefs.insert(key_name, list_key.into());

    storage::add_contract_version(
        metadata_hash,
        access_uref,
        SemVer::V1_0_0,
        methods,
        mailing_list_urefs,
    )
    .unwrap_or_revert();
}
