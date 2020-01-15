#![no_std]

extern crate alloc;

use alloc::{borrow::ToOwned, collections::BTreeMap, string::String};
use core::iter;

use contract::{
    contract_api::{runtime, storage},
    unwrap_or_revert::UnwrapOrRevert,
};
use types::{ApiError, Key};

const BAR_KEY: &str = "Bar";
const FOO_KEY: &str = "Foo";
const LIST_NAMED_KEYS_EXT: &str = "list_named_keys_ext";
const LIST_NAMED_KEYS_KEY: &str = "list_named_keys";
const TEST_TUREF: &str = "Test";

#[no_mangle]
pub extern "C" fn list_named_keys_ext() {
    let passed_in_uref = runtime::get_key(FOO_KEY).unwrap_or_revert_with(ApiError::GetKey);
    let uref = storage::new_turef(TEST_TUREF);
    runtime::put_key(BAR_KEY, uref.clone().into());
    let contracts_named_keys = runtime::list_named_keys();
    let expected_urefs: BTreeMap<String, Key> = {
        let mut tmp = BTreeMap::new();
        tmp.insert(BAR_KEY.to_owned(), uref.into());
        tmp.insert(FOO_KEY.to_owned(), passed_in_uref);
        tmp
    };
    // Test that `list_named_keys` returns correct value when in the subcall (contract).
    assert_eq!(expected_urefs, contracts_named_keys);
}

#[no_mangle]
pub extern "C" fn call() {
    let turef = storage::new_turef(1i32);
    runtime::put_key(FOO_KEY, turef.clone().into());
    let _accounts_named_keys = runtime::list_named_keys();
    let expected_urefs: BTreeMap<String, Key> =
        iter::once((FOO_KEY.to_owned(), turef.into())).collect();
    // Test that `list_named_keys` returns correct value when called in the context of an account.
    // Store `list_named_keys_ext` to be called in the `call` part of this contract.
    // We don't have to  pass `expected_urefs` to exercise this function but
    // it adds initial known urefs to the state of the contract.
    let pointer = storage::store_function_at_hash(LIST_NAMED_KEYS_EXT, expected_urefs);
    runtime::put_key(LIST_NAMED_KEYS_KEY, pointer.into())
}
