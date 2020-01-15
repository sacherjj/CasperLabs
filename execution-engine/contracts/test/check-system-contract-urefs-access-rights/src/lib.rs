#![no_std]

use contract::contract_api::runtime;
use types::{Key, URef};

#[allow(clippy::redundant_closure)]
#[no_mangle]
pub extern "C" fn call() {
    let named_keys = runtime::list_named_keys();
    let mut access_rights_iter = named_keys
        .values()
        .filter_map(Key::as_uref)
        .filter_map(URef::access_rights);

    assert!(access_rights_iter.all(|ar| ar.is_readable()));
    assert!(access_rights_iter.all(|ar| !ar.is_addable()));
    assert!(access_rights_iter.all(|ar| !ar.is_writeable()));
}
