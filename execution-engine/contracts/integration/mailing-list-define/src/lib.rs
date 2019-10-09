#![no_std]

#[macro_use]
extern crate alloc;

extern crate contract_ffi;

use alloc::collections::BTreeMap;
use alloc::string::String;
use alloc::vec::Vec;

use contract_ffi::contract_api::pointers::TURef;
use contract_ffi::contract_api::{self, Error};
use contract_ffi::key::Key;
use contract_ffi::unwrap_or_revert::UnwrapOrRevert;
use contract_ffi::uref::URef;

fn get_list_key(name: &str) -> TURef<Vec<String>> {
    contract_api::runtime::get_key(name)
        .unwrap()
        .to_turef()
        .unwrap()
}

fn update_list(name: String) {
    let list_key = get_list_key("list");
    let mut list = contract_api::storage::read(list_key.clone())
        .unwrap_or_revert_with(Error::Read)
        .unwrap_or_revert_with(Error::ValueNotFound);
    list.push(name);
    contract_api::storage::write(list_key, list);
}

fn sub(name: String) -> Option<TURef<Vec<String>>> {
    if contract_api::runtime::has_key(&name) {
        let init_message = vec![String::from("Hello again!")];
        let new_key = contract_api::storage::new_turef(init_message);
        Some(new_key) //already subscribed
    } else {
        let init_message = vec![String::from("Welcome!")];
        let new_key = contract_api::storage::new_turef(init_message);
        contract_api::runtime::put_key(&name, &new_key.clone().into());
        update_list(name);
        Some(new_key)
    }
}

fn publish(msg: String) {
    let curr_list = contract_api::storage::read(get_list_key("list"))
        .unwrap_or_revert_with(Error::Read)
        .unwrap_or_revert_with(Error::ValueNotFound);
    for name in curr_list.iter() {
        let uref = get_list_key(name);
        let mut messages = contract_api::storage::read(uref.clone())
            .unwrap_or_revert_with(Error::Read)
            .unwrap_or_revert_with(Error::ValueNotFound);
        messages.push(msg.clone());
        contract_api::storage::write(uref, messages);
    }
}

#[no_mangle]
pub extern "C" fn mailing_list_ext() {
    let method_name: String = contract_api::runtime::get_arg(0)
        .unwrap_or_revert_with(Error::MissingArgument)
        .unwrap_or_revert_with(Error::InvalidArgument);
    match method_name.as_str() {
        "sub" => match sub(contract_api::runtime::get_arg(1).unwrap().unwrap()) {
            Some(turef) => {
                let extra_uref = URef::new(turef.addr(), turef.access_rights());
                let extra_urefs = vec![extra_uref];
                contract_api::runtime::ret(&Some(Key::from(turef)), &extra_urefs);
            }
            _ => contract_api::runtime::ret(&Option::<Key>::None, &Vec::new()),
        },
        //Note that this is totally insecure. In reality
        //the pub method would be only available under an
        //unforgable reference because otherwise anyone could
        //spam the mailing list.
        "pub" => {
            publish(contract_api::runtime::get_arg(1).unwrap().unwrap());
        }
        _ => panic!("Unknown method name!"),
    }
}

#[no_mangle]
pub extern "C" fn call() {
    let init_list: Vec<String> = Vec::new();
    let list_key = contract_api::storage::new_turef(init_list);

    //create map of references for stored contract
    let mut mailing_list_urefs: BTreeMap<String, Key> = BTreeMap::new();
    let key_name = String::from("list");
    mailing_list_urefs.insert(key_name, list_key.into());

    let pointer =
        contract_api::storage::store_function_at_hash("mailing_list_ext", mailing_list_urefs);
    contract_api::runtime::put_key("mailing", &pointer.into())
}
