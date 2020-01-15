#![no_std]

extern crate alloc;

use alloc::string::{String, ToString};
use core::convert::TryInto;

use contract::{
    contract_api::{runtime, storage},
    unwrap_or_revert::UnwrapOrRevert,
};
use types::{ApiError, Key, U512};

#[no_mangle]
pub extern "C" fn call() {
    let initi_uref_num = 2; // TODO: this is very brittle as it breaks whenever we add another default uref

    // Account starts with two known urefs: mint uref & pos uref
    if runtime::list_named_keys().len() != initi_uref_num {
        runtime::revert(ApiError::User(201));
    }

    // Add new urefs
    let hello_world_key: Key = storage::new_turef(String::from("Hello, world!")).into();
    runtime::put_key("hello-world", hello_world_key);
    assert_eq!(runtime::list_named_keys().len(), initi_uref_num + 1);

    // Verify if the uref is present
    assert!(runtime::has_key("hello-world"));

    let big_value_key: Key = storage::new_turef(U512::max_value()).into();
    runtime::put_key("big-value", big_value_key);

    assert_eq!(runtime::list_named_keys().len(), initi_uref_num + 2);

    // Read data hidden behind `URef1` uref
    let hello_world: String = storage::read(
        runtime::list_named_keys()
            .get("hello-world")
            .expect("Unable to get hello-world")
            .clone()
            .try_into()
            .expect("Unable to convert to turef"),
    )
    .expect("Unable to deserialize TURef")
    .expect("Unable to find value");
    assert_eq!(hello_world, "Hello, world!");

    // Read data through dedicated FFI function
    let uref1 = runtime::get_key("hello-world").unwrap_or_revert();

    let turef = uref1.try_into().unwrap_or_revert_with(ApiError::User(101));
    let hello_world = storage::read(turef);
    assert_eq!(hello_world, Ok(Some("Hello, world!".to_string())));

    // Remove uref
    runtime::remove_key("hello-world");
    assert!(!runtime::has_key("hello-world"));

    // Confirm URef2 is still there
    assert!(runtime::has_key("big-value"));

    // Get the big value back
    let big_value_key = runtime::get_key("big-value").unwrap_or_revert_with(ApiError::User(102));
    let big_value_ref = big_value_key.try_into().unwrap_or_revert();
    let big_value = storage::read(big_value_ref);
    assert_eq!(big_value, Ok(Some(U512::max_value())));

    // Increase by 1
    storage::add(big_value_ref, U512::one());
    let new_big_value = storage::read(big_value_ref);
    assert_eq!(new_big_value, Ok(Some(U512::zero())));

    // I can overwrite some data under the pointer
    storage::write(big_value_ref, U512::from(123_456_789u64));
    let new_value = storage::read(big_value_ref);
    assert_eq!(new_value, Ok(Some(U512::from(123_456_789u64))));

    // Try to remove non existing uref which shouldn't fail
    runtime::remove_key("hello-world");
    // Remove a valid uref
    runtime::remove_key("big-value");

    // Cleaned up state
    assert!(!runtime::has_key("hello-world"));
    assert!(!runtime::has_key("big-value"));

    assert_eq!(runtime::list_named_keys().len(), initi_uref_num);
}
