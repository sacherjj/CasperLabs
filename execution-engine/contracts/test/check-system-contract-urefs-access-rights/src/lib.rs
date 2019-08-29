#![no_std]
#![feature(cell_update)]

extern crate alloc;
extern crate contract_ffi;

use contract_ffi::contract_api::list_known_urefs;
use contract_ffi::key::Key;
use contract_ffi::uref::URef;

#[allow(clippy::redundant_closure)]
#[no_mangle]
pub extern "C" fn call() {
    let known_urefs = list_known_urefs();
    let mut known_urefs_access_rights = known_urefs
        .values()
        .filter_map(Key::as_uref)
        .filter_map(URef::access_rights);

    assert!(known_urefs_access_rights.all(|ar| ar.is_readable()));
    assert!(known_urefs_access_rights.all(|ar| !ar.is_addable()));
    assert!(known_urefs_access_rights.all(|ar| !ar.is_writeable()));
}
