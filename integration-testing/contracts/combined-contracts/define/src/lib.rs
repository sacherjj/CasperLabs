#![no_std]
#![feature(alloc)]
#[macro_use]

extern crate alloc;
extern crate common;

use alloc::collections::BTreeMap;
use alloc::string::String;
use alloc::vec::Vec;

use common::contract_api::*;
use common::contract_api::pointers::UPointer;
use common::key::Key;
use common::uref::URef;

fn hello_name(name: &str) -> String {
    let mut result = String::from("Hello, ");
    result.push_str(name);
    result
}

#[no_mangle]
pub extern "C" fn hello_name_ext() {
    let name: String = get_arg(0);
    let y = hello_name(&name);
    ret(&y, &Vec::new());
}


fn get_list_key(name: &str) -> UPointer<Vec<String>> {
    get_uref(name).unwrap().to_u_ptr().unwrap()
}

fn update_list(name: String) {
    let list_key = get_list_key("list");
    let mut list = read(list_key.clone());
    list.push(name);
    write(list_key, list);
}

fn sub(name: String) -> Option<UPointer<Vec<String>>> {
    if has_uref(&name) {
        let init_message = vec![String::from("Hello again!")];
        let new_key = new_uref(init_message);
        Some(new_key) //already subscribed
    } else {
        let init_message = vec![String::from("Welcome!")];
        let new_key = new_uref(init_message);
        add_uref(&name, &new_key.clone().into());
        update_list(name);
        Some(new_key)
    }
}

fn publish(msg: String) {
    let curr_list = read(get_list_key("list"));
    for name in curr_list.iter() {
        let uref = get_list_key(name);
        let mut messages = read(uref.clone());
        messages.push(msg.clone());
        write(uref, messages);
    }
}

 #[no_mangle]
 pub extern "C" fn mailing_list_ext() {
     let method_name: String = get_arg(0);
     match method_name.as_str() {
         "sub" => match sub(get_arg(1)) {
             Some(upointer) => {
                 let extra_uref = URef::new(upointer.0, upointer.1);
                 let extra_urefs = vec![extra_uref];
                 ret(&Some(Key::from(upointer)), &extra_urefs);
             }
             _ => ret(&Option::<Key>::None, &Vec::new()),
         },
         //Note that this is totally insecure. In reality
         //the pub method would be only available under an
         //unforgable reference because otherwise anyone could
         //spam the mailing list.
         "pub" => {
             publish(get_arg(1));
         }
         _ => panic!("Unknown method name!"),
     }
 }

#[no_mangle]
pub extern "C" fn counter_ext() {
    let i_key: UPointer<i32> = get_uref("count").unwrap().to_u_ptr().unwrap();
    let method_name: String = get_arg(0);
    match method_name.as_str() {
        "inc" => add(i_key, 1),
        "get" => {
            let result = read(i_key);
            ret(&result, &Vec::new());
        }
        _ => panic!("Unknown method name!"),
    }
}

#[no_mangle]
pub extern "C" fn call() {
    // hello_name
    let pointer = store_function("hello_name_ext", BTreeMap::new());
    add_uref("hello_name", &pointer.into());

    // counter
    let counter_local_key = new_uref(0); //initialize counter

    //create map of references for stored contract
    let mut counter_urefs: BTreeMap<String, Key> = BTreeMap::new();
    let key_name = String::from("count");
    counter_urefs.insert(key_name, counter_local_key.into());
    let _counter_hash = store_function("counter_ext", counter_urefs);
    add_uref("counter", &_counter_hash.into());

    // mailing list
    let init_list: Vec<String> = Vec::new();
    let list_key = new_uref(init_list);

    //create map of references for stored contract
    let mut mailing_list_urefs: BTreeMap<String, Key> = BTreeMap::new();
    let key_name = String::from("list");
    mailing_list_urefs.insert(key_name, list_key.into());

    let pointer = store_function("mailing_list_ext", mailing_list_urefs);
    add_uref("mailing", &pointer.into())
}
