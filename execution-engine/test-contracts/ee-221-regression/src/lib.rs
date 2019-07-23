#![no_std]
#![feature(alloc, cell_update)]

extern crate alloc;

extern crate cl_std;

use cl_std::contract_api::{add_uref, get_uref, new_uref, revert};
use cl_std::key::Key;
use cl_std::uref::URef;

#[no_mangle]
pub extern "C" fn call() {
    let res1 = get_uref("nonexistinguref");
    assert!(res1.is_none());

    let new_uref: URef = new_uref(()).into();
    add_uref("nonexistinguref", &Key::URef(new_uref));

    let res2 = get_uref("nonexistinguref");

    assert_eq!(res2.unwrap_or_else(|| revert(101)), new_uref.into());
}
