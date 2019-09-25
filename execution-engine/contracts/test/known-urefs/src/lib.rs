#![no_std]
#![feature(cell_update)]

extern crate alloc;
extern crate contract_ffi;

use alloc::string::{String, ToString};

use contract_ffi::contract_api;
use contract_ffi::key::Key;
use contract_ffi::value::U512;

#[no_mangle]
pub extern "C" fn call() {
    let initi_uref_num = 2; // TODO: this is very brittle as it breaks whenever we add another default uref

    // Account starts with two known urefs: mint uref & pos uref
    if contract_api::list_known_urefs().len() != initi_uref_num {
        contract_api::revert(201);
    }

    // Add new urefs
    let hello_world_key: Key = contract_api::new_turef(String::from("Hello, world!")).into();
    contract_api::add_uref("hello-world", &hello_world_key);
    assert_eq!(contract_api::list_known_urefs().len(), initi_uref_num + 1);

    // Verify if the uref is present
    assert!(contract_api::has_uref("hello-world"));

    let big_value_key: Key = contract_api::new_turef(U512::max_value()).into();
    contract_api::add_uref("big-value", &big_value_key);

    assert_eq!(contract_api::list_known_urefs().len(), initi_uref_num + 2);

    // Read data hidden behind `URef1` uref
    let hello_world: String = contract_api::read(
        contract_api::list_known_urefs()
            .get("hello-world")
            .expect("Unable to get hello-world")
            .to_turef()
            .expect("Unable to convert to turef"),
    )
    .expect("Unable to deserialize TURef")
    .expect("Unable to find value");
    assert_eq!(hello_world, "Hello, world!");

    // Read data through dedicated FFI function
    let uref1 = contract_api::get_uref("hello-world").unwrap_or_else(|| contract_api::revert(100));
    let turef = uref1
        .to_turef()
        .unwrap_or_else(|| contract_api::revert(101));
    let hello_world = contract_api::read(turef);
    assert_eq!(hello_world, Ok(Some("Hello, world!".to_string())));

    // Remove uref
    contract_api::remove_uref("hello-world");
    assert!(!contract_api::has_uref("hello-world"));

    // Confirm URef2 is still there
    assert!(contract_api::has_uref("big-value"));

    // Get the big value back
    let big_value_key =
        contract_api::get_uref("big-value").unwrap_or_else(|| contract_api::revert(102));
    let big_value_ref = big_value_key
        .to_turef()
        .expect("Unable to get turef for big-value");
    let big_value = contract_api::read(big_value_ref);
    assert_eq!(big_value, Ok(Some(U512::max_value())));

    // Increase by 1
    contract_api::add(big_value_ref, U512::one());
    let new_big_value = contract_api::read(big_value_ref);
    assert_eq!(new_big_value, Ok(Some(U512::zero())));

    // I can overwrite some data under the pointer
    contract_api::write(big_value_ref, U512::from(123_456_789u64));
    let new_value = contract_api::read(big_value_ref);
    assert_eq!(new_value, Ok(Some(U512::from(123_456_789u64))));

    // Try to remove non existing uref which shouldn't fail
    contract_api::remove_uref("hello-world");
    // Remove a valid uref
    contract_api::remove_uref("big-value");

    // Cleaned up state
    assert!(!contract_api::has_uref("hello-world"));
    assert!(!contract_api::has_uref("big-value"));
    assert_eq!(contract_api::list_known_urefs().len(), initi_uref_num);
}
