#![no_std]

extern crate alloc;

use alloc::{collections::BTreeMap, string::String, vec};

use contract_ffi::{
    contract_api::{runtime, storage, ContractRef, Error},
    key::Key,
    unwrap_or_revert::UnwrapOrRevert,
    uref::URef,
    value::{CLValue, U512},
};

#[no_mangle]
pub extern "C" fn do_nothing() {
    // Doesn't advance RNG of the runtime
    runtime::ret(CLValue::from_t("Hello, world!").unwrap_or_revert(), vec![])
}

#[no_mangle]
pub extern "C" fn do_something() {
    // Advances RNG of the runtime
    let test_string = String::from("Hello, world!");

    let test_uref = storage::new_turef(test_string).into();
    let return_value = CLValue::from_t(test_uref).unwrap_or_revert();
    runtime::ret(return_value, vec![test_uref])
}

#[no_mangle]
pub extern "C" fn call() {
    let flag: String = runtime::get_arg(0)
        .unwrap_or_revert_with(Error::MissingArgument)
        .unwrap_or_revert_with(Error::InvalidArgument);
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
        let result: String = runtime::call_contract(do_nothing.clone(), (), vec![])
            .to_t()
            .unwrap_or_revert();
        assert_eq!(result, "Hello, world!");
        let uref2: URef = storage::new_turef(U512::from(1)).into();
        runtime::put_key("uref2", Key::URef(uref2));
    } else if flag == "pass3" {
        let uref1: URef = storage::new_turef(U512::from(0)).into();
        runtime::put_key("uref1", Key::URef(uref1));
        // do_something returns a new uref, and it should forward the internal RNG.
        let cl_value: CLValue = runtime::call_contract(do_something.clone(), (), vec![]);
        let uref2: URef = cl_value.to_t().unwrap();
        runtime::put_key("uref2", Key::URef(uref2));
    }
}
