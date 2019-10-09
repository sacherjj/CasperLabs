#![no_std]

extern crate alloc;

extern crate contract_ffi;

use alloc::string::{String, ToString};

use contract_ffi::contract_api::{self, Error};
use contract_ffi::key::Key;
use contract_ffi::unwrap_or_revert::UnwrapOrRevert;
use contract_ffi::value::U512;

#[no_mangle]
pub extern "C" fn call() {
    let initi_uref_num = 2; // TODO: this is very brittle as it breaks whenever we add another default uref

    // Account starts with two known urefs: mint uref & pos uref
    if contract_api::runtime::list_named_keys().len() != initi_uref_num {
        contract_api::runtime::revert(Error::User(201));
    }

    // Add new urefs
    let hello_world_key: Key =
        contract_api::storage::new_turef(String::from("Hello, world!")).into();
    contract_api::runtime::put_key("hello-world", &hello_world_key);
    assert_eq!(
        contract_api::runtime::list_named_keys().len(),
        initi_uref_num + 1
    );

    // Verify if the uref is present
    assert!(contract_api::runtime::has_key("hello-world"));

    let big_value_key: Key = contract_api::storage::new_turef(U512::max_value()).into();
    contract_api::runtime::put_key("big-value", &big_value_key);

    assert_eq!(
        contract_api::runtime::list_named_keys().len(),
        initi_uref_num + 2
    );

    // Read data hidden behind `URef1` uref
    let hello_world: String = contract_api::storage::read(
        contract_api::runtime::list_named_keys()
            .get("hello-world")
            .expect("Unable to get hello-world")
            .to_turef()
            .expect("Unable to convert to turef"),
    )
    .expect("Unable to deserialize TURef")
    .expect("Unable to find value");
    assert_eq!(hello_world, "Hello, world!");

    // Read data through dedicated FFI function
    let uref1 = contract_api::runtime::get_key("hello-world")
        .unwrap_or_else(|| contract_api::runtime::revert(Error::User(100)));
    let turef = uref1.to_turef().unwrap_or_revert_with(Error::User(101));
    let hello_world = contract_api::storage::read(turef);
    assert_eq!(hello_world, Ok(Some("Hello, world!".to_string())));

    // Remove uref
    contract_api::runtime::remove_key("hello-world");
    assert!(!contract_api::runtime::has_key("hello-world"));

    // Confirm URef2 is still there
    assert!(contract_api::runtime::has_key("big-value"));

    // Get the big value back
    let big_value_key = contract_api::runtime::get_key("big-value")
        .unwrap_or_else(|| contract_api::runtime::revert(Error::User(102)));
    let big_value_ref = big_value_key
        .to_turef()
        .expect("Unable to get turef for big-value");
    let big_value = contract_api::storage::read(big_value_ref);
    assert_eq!(big_value, Ok(Some(U512::max_value())));

    // Increase by 1
    contract_api::storage::add(big_value_ref, U512::one());
    let new_big_value = contract_api::storage::read(big_value_ref);
    assert_eq!(new_big_value, Ok(Some(U512::zero())));

    // I can overwrite some data under the pointer
    contract_api::storage::write(big_value_ref, U512::from(123_456_789u64));
    let new_value = contract_api::storage::read(big_value_ref);
    assert_eq!(new_value, Ok(Some(U512::from(123_456_789u64))));

    // Try to remove non existing uref which shouldn't fail
    contract_api::runtime::remove_key("hello-world");
    // Remove a valid uref
    contract_api::runtime::remove_key("big-value");

    // Cleaned up state
    assert!(!contract_api::runtime::has_key("hello-world"));
    assert!(!contract_api::runtime::has_key("big-value"));
    assert_eq!(
        contract_api::runtime::list_named_keys().len(),
        initi_uref_num
    );
}
