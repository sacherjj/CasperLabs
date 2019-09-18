#![no_std]
#![feature(cell_update)]

extern crate alloc;
extern crate contract_ffi;

use alloc::string::String;

use contract_ffi::contract_api::{
    add, add_uref, get_uref, has_uref, list_known_urefs, new_turef, read, remove_uref, revert,
    write,
};
use contract_ffi::key::Key;
use contract_ffi::value::U512;

#[no_mangle]
pub extern "C" fn call() {
    let initi_uref_num = 4; // TODO: this is very brittle as it breaks whenever we add another default uref

    // Account starts with FOUR known urefs: genesis_acct, mint public uref, mint
    // private uref, pos public uref & pos private uref.
    if list_known_urefs().len() != initi_uref_num {
        revert(201);
    }

    // Add new urefs
    let hello_world_key: Key = new_turef(String::from("Hello, world!")).into();
    add_uref("hello-world", &hello_world_key);
    assert_eq!(list_known_urefs().len(), initi_uref_num + 1);

    // Verify if the uref is present
    assert!(has_uref("hello-world"));

    let big_value_key: Key = new_turef(U512::max_value()).into();
    add_uref("big-value", &big_value_key);

    assert_eq!(list_known_urefs().len(), initi_uref_num + 2);

    // Read data hidden behind `URef1` uref
    let hello_world: String = read(
        list_known_urefs()
            .get("hello-world")
            .expect("Unable to get hello-world")
            .to_turef()
            .expect("Unable to convert to turef"),
    );
    assert_eq!(hello_world, "Hello, world!");

    // Read data through dedicated FFI function
    let uref1 = get_uref("hello-world").unwrap_or_else(|| revert(100));
    let turef = uref1.to_turef().unwrap_or_else(|| revert(101));
    let hello_world: String = read(turef);
    assert_eq!(hello_world, "Hello, world!");

    // Remove uref
    remove_uref("hello-world");
    assert!(!has_uref("hello-world"));

    // Confirm URef2 is still there
    assert!(has_uref("big-value"));

    // Get the big value back
    let big_value_key = get_uref("big-value").unwrap_or_else(|| revert(102));
    let big_value_ref = big_value_key
        .to_turef()
        .expect("Unable to get turef for big-value");
    let big_value: U512 = read(big_value_ref);
    assert_eq!(big_value, U512::max_value());

    // Increase by 1
    add(big_value_ref, U512::one());
    let new_big_value: U512 = read(big_value_ref);
    assert_eq!(new_big_value, U512::zero());

    // I can overwrite some data under the pointer
    write(big_value_ref, U512::from(123_456_789u64));
    let new_value: U512 = read(big_value_ref);
    assert_eq!(new_value, U512::from(123_456_789u64));

    // Try to remove non existing uref which shouldn't fail
    remove_uref("hello-world");
    // Remove a valid uref
    remove_uref("big-value");

    // Cleaned up state
    assert!(!has_uref("hello-world"));
    assert!(!has_uref("big-value"));
    assert_eq!(list_known_urefs().len(), initi_uref_num);
}
