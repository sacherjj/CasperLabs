#![no_std]
#![feature(alloc, cell_update)]

extern crate alloc;
extern crate cl_std;

mod capabilities;

// These types are purposely defined in a separate module
// so that their constructors are hidden and therefore
// we must use the conversion methods from Key elsewhere
// in the code.
mod internal_purse_id;

mod mint;

use alloc::collections::BTreeMap;
use cl_std::contract_api;

#[no_mangle]
pub extern "C" fn call() {
    let _hash = contract_api::store_function("???", BTreeMap::new());
}
