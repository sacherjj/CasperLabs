#![no_std]

extern crate alloc;

use alloc::{collections::BTreeMap, string::String, vec::Vec};

use contract::{contract_api::runtime, unwrap_or_revert::UnwrapOrRevert};
use types::{ApiError, Key};

enum Arg {
    InitialNamedKeys = 0,
    NewNamedKeys,
}

#[no_mangle]
pub extern "C" fn call() {
    // Account starts with two known named keys: mint uref & pos uref.
    let expected_initial_named_keys: BTreeMap<String, Key> =
        runtime::get_arg(Arg::InitialNamedKeys as u32)
            .unwrap_or_revert_with(ApiError::MissingArgument)
            .unwrap_or_revert_with(ApiError::InvalidArgument);

    let actual_named_keys = runtime::list_named_keys();
    assert_eq!(expected_initial_named_keys, actual_named_keys);

    // Add further named keys and assert that each is returned in `list_named_keys()`.
    let new_named_keys: BTreeMap<String, Key> = runtime::get_arg(Arg::NewNamedKeys as u32)
        .unwrap_or_revert_with(ApiError::MissingArgument)
        .unwrap_or_revert_with(ApiError::InvalidArgument);
    let mut expected_named_keys = expected_initial_named_keys;

    for (key, value) in new_named_keys {
        runtime::put_key(&key, value);
        assert!(expected_named_keys.insert(key, value).is_none());
        let actual_named_keys = runtime::list_named_keys();
        assert_eq!(expected_named_keys, actual_named_keys);
    }

    // Remove all named keys and check that removed keys aren't returned in `list_named_keys()`.
    let all_key_names: Vec<String> = expected_named_keys.keys().cloned().collect();
    for key in all_key_names {
        runtime::remove_key(&key);
        assert!(expected_named_keys.remove(&key).is_some());
        let actual_named_keys = runtime::list_named_keys();
        assert_eq!(expected_named_keys, actual_named_keys);
    }
}
