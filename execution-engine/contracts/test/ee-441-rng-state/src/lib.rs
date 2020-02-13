#![no_std]

extern crate alloc;

use alloc::{collections::BTreeMap, string::String};

use contract::{
    contract_api::{runtime, storage},
    unwrap_or_revert::UnwrapOrRevert,
};
use types::{ApiError, CLValue, ContractRef, Key, URef, U512};

#[no_mangle]
pub extern "C" fn do_nothing() {
    // Doesn't advance RNG of the runtime
    runtime::ret(CLValue::from_t("Hello, world!").unwrap_or_revert())
}

#[no_mangle]
pub extern "C" fn do_something() {
    // Advances RNG of the runtime
    let test_string = String::from("Hello, world!");

    let test_uref = URef::from(storage::new_turef(test_string));
    let return_value = CLValue::from_t(test_uref).unwrap_or_revert();
    runtime::ret(return_value)
}

#[no_mangle]
pub extern "C" fn call() {
    let flag: String = runtime::get_arg(0)
        .unwrap_or_revert_with(ApiError::MissingArgument)
        .unwrap_or_revert_with(ApiError::InvalidArgument);
    let do_nothing: ContractRef = storage::store_function_at_hash("do_nothing", BTreeMap::new());
    let do_something: ContractRef =
        storage::store_function_at_hash("do_something", BTreeMap::new());
    if flag == "pass1" {
        // Two calls should forward the internal RNG. This pass is a baseline.
        let uref1: URef = storage::new_turef(U512::from(0)).into();
        let uref2: URef = storage::new_turef(U512::from(1)).into();
        runtime::put_key("uref1", Key::URef(uref1));
        runtime::put_key("uref2", Key::URef(uref2));
    } else if flag == "pass2" {
        let uref1: URef = storage::new_turef(U512::from(0)).into();
        runtime::put_key("uref1", Key::URef(uref1));
        // do_nothing doesn't do anything. It SHOULD not forward the internal RNG.
        let result: String = runtime::call_contract(do_nothing, ());
        assert_eq!(result, "Hello, world!");
        let uref2: URef = storage::new_turef(U512::from(1)).into();
        runtime::put_key("uref2", Key::URef(uref2));
    } else if flag == "pass3" {
        let uref1: URef = storage::new_turef(U512::from(0)).into();
        runtime::put_key("uref1", Key::URef(uref1));
        // do_something returns a new uref, and it should forward the internal RNG.
        let uref2: URef = runtime::call_contract(do_something, ());
        runtime::put_key("uref2", Key::URef(uref2));
    }
}
