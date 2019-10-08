#![no_std]

extern crate alloc;

extern crate contract_ffi;

use alloc::string::String;
use alloc::vec::Vec;

use contract_ffi::contract_api::pointers::ContractPointer;
use contract_ffi::contract_api::{self, Error};
use contract_ffi::key::Key;
use contract_ffi::unwrap_or_revert::UnwrapOrRevert;
use contract_ffi::value::Value;

#[no_mangle]
pub extern "C" fn call() {
    let hello_name_uref =
        contract_api::get_key("hello_name").unwrap_or_revert_with(Error::User(100));
    let pointer = if let Key::Hash(hash) = hello_name_uref {
        ContractPointer::Hash(hash)
    } else {
        contract_api::revert(Error::User(66)); // exit code is currently arbitrary
    };

    let arg = "World";
    let result: String = contract_api::call_contract(pointer, &(arg,), &Vec::new());
    assert_eq!("Hello, World", result);

    //store the result at a uref so it can be seen as an effect on the global state
    contract_api::put_key(
        "helloworld",
        &contract_api::new_turef(Value::String(result)).into(),
    );
}
