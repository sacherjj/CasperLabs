#![no_std]
#![feature(cell_update)]

extern crate alloc;
extern crate contract_ffi;

use alloc::string::String;

use contract_ffi::contract_api::{
    add, add_uref, get_uref, has_uref, list_known_urefs, new_uref, read, remove_uref, revert, write,
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
    let hello_world_uref1: Key = new_uref(String::from("Hello, world!")).into();
    add_uref("URef1", &hello_world_uref1);
    assert_eq!(list_known_urefs().len(), initi_uref_num + 1);

    // Verify if the uref is present
    assert!(has_uref("URef1"));

    let big_value_uref: Key = new_uref(U512::max_value()).into();
    add_uref("URef2", &big_value_uref);

    assert_eq!(list_known_urefs().len(), initi_uref_num + 2);

    // Read data hidden behind `URef1` uref
    let hello_world: String = read(
        list_known_urefs()
            .get("URef1")
            .expect("Unable to get URef1")
            .to_u_ptr()
            .expect("Unable to convert to u_ptr"),
    );
    assert_eq!(hello_world, "Hello, world!");

    // Read data through dedicated FFI function
    let uref1 = get_uref("URef1").unwrap_or_else(|| revert(100));
    let uref1_ptr = uref1.to_u_ptr().unwrap_or_else(|| revert(101));
    let hello_world: String = read(uref1_ptr);
    assert_eq!(hello_world, "Hello, world!");

    // Remove uref
    remove_uref("URef1");
    assert!(!has_uref("URef1"));

    // Confirm URef2 is still there
    assert!(has_uref("URef2"));

    // Get the big value back
    let big_value_key = get_uref("URef2").unwrap_or_else(|| revert(102));
    let big_value_uptr = big_value_key
        .to_u_ptr()
        .expect("Unable to get uptr for URef2");
    let big_value: U512 = read(big_value_uptr);
    assert_eq!(big_value, U512::max_value());

    // Increase by 1
    add(big_value_uptr, U512::one());
    let new_big_value: U512 = read(big_value_uptr);
    assert_eq!(new_big_value, U512::zero());

    // I can overwrite some data under the pointer
    write(big_value_uptr, U512::from(123_456_789u64));
    let new_value: U512 = read(big_value_uptr);
    assert_eq!(new_value, U512::from(123_456_789u64));

    // Try to remove non existing uref which shouldn't fail
    remove_uref("URef1");
    // Remove a valid uref
    remove_uref("URef2");

    // Cleaned up state
    assert!(!has_uref("URef1"));
    assert!(!has_uref("URef2"));
    assert_eq!(list_known_urefs().len(), initi_uref_num);
}
