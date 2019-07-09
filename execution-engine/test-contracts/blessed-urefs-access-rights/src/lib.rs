#![no_std]
#![feature(alloc, cell_update)]

extern crate alloc;
extern crate cl_std;

use cl_std::contract_api::list_known_urefs;
use cl_std::key::Key;
use cl_std::uref::URef;

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
