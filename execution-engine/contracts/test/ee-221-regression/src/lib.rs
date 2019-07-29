#![no_std]
#![feature(alloc, cell_update)]

extern crate alloc;

extern crate cl_std;

use cl_std::contract_api::{add_uref, get_uref, new_uref};
use cl_std::key::Key;

#[no_mangle]
pub extern "C" fn call() {
    let res1 = get_uref("nonexistinguref");
    assert!(res1.is_none());

    let key = Key::URef(new_uref(()).into());
    add_uref("nonexistinguref", &key);

    let res2 = get_uref("nonexistinguref");

    assert_eq!(res2, Some(key));
}
