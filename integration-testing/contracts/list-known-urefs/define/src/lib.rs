#![no_std]

extern crate alloc;
extern crate contract_ffi;

use alloc::borrow::ToOwned;
use alloc::collections::btree_map::BTreeMap;
use alloc::string::String;
use contract_ffi::contract_api::{
    put_key, get_key, list_named_keys, new_turef, revert, store_function_at_hash,
};
use contract_ffi::key::Key;
use contract_ffi::value::Value;
use core::iter;

#[no_mangle]
pub extern "C" fn list_named_keys_ext() {
    let passed_in_uref = get_key("Foo").unwrap_or_else(|| revert(100));
    let uref = new_turef(Value::String("Test".to_owned()));
    put_key("Bar", &uref.clone().into());
    let contracts_named_keys = list_named_keys();
    let expected_urefs: BTreeMap<String, Key> = {
        let mut tmp = BTreeMap::new();
        tmp.insert("Bar".to_owned(), uref.into());
        tmp.insert("Foo".to_owned(), passed_in_uref);
        tmp
    };
    // Test that `list_named_keys` returns correct value when in the subcall (contract).
    assert_eq!(expected_urefs, contracts_named_keys);
}

#[no_mangle]
pub extern "C" fn call() {
    let turef = new_turef(Value::Int32(1));
    put_key("Foo", &turef.clone().into());
    let _accounts_named_keys = list_named_keys();
    let expected_urefs: BTreeMap<String, Key> =
        iter::once(("Foo".to_owned(), turef.into())).collect();
    // Test that `list_named_keys` returns correct value when called in the context of an account.
    // Store `list_named_keys_ext` to be called in the `call` part of this contract.
    // We don't have to  pass `expected_urefs` to exercise this function but
    // it adds initial known urefs to the state of the contract.
    let pointer = store_function_at_hash("list_named_keys_ext", expected_urefs);
    put_key("list_named_keys", &pointer.into())
}
