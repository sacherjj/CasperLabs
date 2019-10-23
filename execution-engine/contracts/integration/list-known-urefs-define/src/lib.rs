#![no_std]

extern crate alloc;

extern crate contract_ffi;

use alloc::borrow::ToOwned;
use alloc::collections::btree_map::BTreeMap;
use alloc::string::String;
use core::iter;

use contract_ffi::contract_api::{runtime, storage, Error};
use contract_ffi::key::Key;
use contract_ffi::unwrap_or_revert::UnwrapOrRevert;
use contract_ffi::value::Value;

#[no_mangle]
pub extern "C" fn list_named_keys_ext() {
    let passed_in_uref = runtime::get_key("Foo").unwrap_or_revert_with(Error::GetKey);
    let uref = storage::new_turef(Value::String("Test".to_owned()));
    runtime::put_key("Bar", &uref.clone().into());
    let contracts_named_keys = runtime::list_named_keys();
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
    let turef = storage::new_turef(Value::Int32(1));
    runtime::put_key("Foo", &turef.clone().into());
    let _accounts_named_keys = runtime::list_named_keys();
    let expected_urefs: BTreeMap<String, Key> =
        iter::once(("Foo".to_owned(), turef.into())).collect();
    // Test that `list_named_keys` returns correct value when called in the context of an account.
    // Store `list_named_keys_ext` to be called in the `call` part of this contract.
    // We don't have to  pass `expected_urefs` to exercise this function but
    // it adds initial known urefs to the state of the contract.
    let pointer = storage::store_function_at_hash("list_named_keys_ext", expected_urefs);
    runtime::put_key("list_named_keys", &pointer.into())
}
