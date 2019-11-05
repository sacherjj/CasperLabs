#![no_std]

extern crate alloc;

use alloc::collections::BTreeMap;
use alloc::string::String;
use alloc::vec;
use alloc::vec::Vec;

use contract_ffi::contract_api::{runtime, storage, Error as ApiError, TURef};
use contract_ffi::key::Key;
use contract_ffi::unwrap_or_revert::UnwrapOrRevert;
use contract_ffi::uref::URef;

const LIST_KEY: &str = "list";
const MAILING_KEY: &str = "mailing";
const MAILING_LIST_EXT: &str = "mailing_list_ext";

enum Arg {
    MethodName = 0,
    Arg1 = 1,
}

#[repr(u16)]
enum Error {
    UnknownMethodName = 0,
}

impl Into<ApiError> for Error {
    fn into(self) -> ApiError {
        ApiError::User(self as u16)
    }
}

fn get_list_key(name: &str) -> TURef<Vec<String>> {
    let key = runtime::get_key(name).unwrap_or_revert_with(ApiError::GetKey);
    key.to_turef()
        .unwrap_or_revert_with(ApiError::UnexpectedKeyVariant)
}

fn update_list(name: String) {
    let list_key = get_list_key(LIST_KEY);
    let mut list = storage::read(list_key.clone())
        .unwrap_or_revert_with(ApiError::Read)
        .unwrap_or_revert_with(ApiError::ValueNotFound);
    list.push(name);
    storage::write(list_key, list);
}

fn sub(name: String) -> Option<TURef<Vec<String>>> {
    if runtime::has_key(&name) {
        let init_message = vec![String::from("Hello again!")];
        Some(storage::new_turef(init_message))
    } else {
        let init_message = vec![String::from("Welcome!")];
        let new_key = storage::new_turef(init_message);
        runtime::put_key(&name, &new_key.clone().into());
        update_list(name);
        Some(new_key)
    }
}

fn publish(msg: String) {
    let curr_list = storage::read(get_list_key(LIST_KEY))
        .unwrap_or_revert_with(ApiError::Read)
        .unwrap_or_revert_with(ApiError::ValueNotFound);
    for name in curr_list.iter() {
        let uref = get_list_key(name);
        let mut messages = storage::read(uref.clone())
            .unwrap_or_revert_with(ApiError::Read)
            .unwrap_or_revert_with(ApiError::ValueNotFound);
        messages.push(msg.clone());
        storage::write(uref, messages);
    }
}

#[no_mangle]
pub extern "C" fn mailing_list_ext() {
    let method_name: String = runtime::get_arg(Arg::MethodName as u32)
        .unwrap_or_revert_with(ApiError::MissingArgument)
        .unwrap_or_revert_with(ApiError::InvalidArgument);
    let arg1: String = runtime::get_arg(Arg::Arg1 as u32)
        .unwrap_or_revert_with(ApiError::MissingArgument)
        .unwrap_or_revert_with(ApiError::InvalidArgument);
    match method_name.as_str() {
        "sub" => match sub(arg1) {
            Some(turef) => {
                let extra_uref = URef::new(turef.addr(), turef.access_rights());
                let extra_urefs = vec![extra_uref];
                runtime::ret(Some(Key::from(turef)), extra_urefs);
            }
            _ => runtime::ret(Option::<Key>::None, Vec::new()),
        },
        //Note that this is totally insecure. In reality
        //the pub method would be only available under an
        //unforgable reference because otherwise anyone could
        //spam the mailing list.
        "pub" => {
            publish(arg1);
        }
        _ => runtime::revert(Error::UnknownMethodName),
    }
}

#[no_mangle]
pub extern "C" fn call() {
    let init_list: Vec<String> = Vec::new();
    let list_key = storage::new_turef(init_list);

    //create map of references for stored contract
    let mut mailing_list_urefs: BTreeMap<String, Key> = BTreeMap::new();
    let key_name = String::from(LIST_KEY);
    mailing_list_urefs.insert(key_name, list_key.into());

    let pointer = storage::store_function_at_hash(MAILING_LIST_EXT, mailing_list_urefs);
    runtime::put_key(MAILING_KEY, &pointer.into())
}
