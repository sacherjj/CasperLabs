#![no_std]
#![feature(alloc, cell_update)]

extern crate alloc;
extern crate cl_std;
use alloc::string::String;
use cl_std::contract_api::{
    add, add_uref, get_uref, has_uref, list_known_urefs, new_uref, read, remove_uref, write,
};
use cl_std::key::Key;
use cl_std::value::U512;

#[no_mangle]
pub extern "C" fn call() {
    // Account starts with two known urefs: mint, and purse_id
    assert_eq!(list_known_urefs().len(), 2);

    // Add new urefs
    let hello_world_uref1: Key = new_uref(String::from("Hello, world!")).into();
    let _ = add_uref("URef1", &hello_world_uref1);
    assert_eq!(list_known_urefs().len(), 3);

    // Verify if the uref is present
    assert!(has_uref("URef1"));

    let big_value_uref: Key = new_uref(U512::max_value()).into();
    let _ = add_uref("URef2", &big_value_uref);

    assert_eq!(list_known_urefs().len(), 4);

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
    let hello_world: String = read(get_uref("URef1").to_u_ptr().expect("Unable to get uptr"));
    assert_eq!(hello_world, "Hello, world!");

    // Remove uref
    remove_uref("URef1");
    assert!(!has_uref("URef1"));

    // Confirm URef2 is still there
    assert!(has_uref("URef2"));

    // Get the big value back
    let big_value_key = get_uref("URef2");
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
    write(big_value_uptr, U512::from(123456789u64));
    let new_value: U512 = read(big_value_uptr);
    assert_eq!(new_value, U512::from(123456789u64));

    // Try to remove non existing uref which shouldn't fail
    remove_uref("URef1");
    // Remove a valid uref
    remove_uref("URef2");

    // Cleaned up state
    assert!(!has_uref("URef1"));
    assert!(!has_uref("URef2"));
    assert_eq!(list_known_urefs().len(), 2);
}
