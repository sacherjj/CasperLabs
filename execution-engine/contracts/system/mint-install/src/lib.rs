#![no_std]

#[macro_use]
extern crate alloc;
extern crate contract_ffi;
extern crate mint_token;

use alloc::collections::BTreeMap;
use contract_ffi::contract_api;
use contract_ffi::uref::URef;

#[no_mangle]
pub extern "C" fn mint_ext() {
    mint_token::delegate();
}

#[no_mangle]
pub extern "C" fn call() {
    let contract = contract_api::fn_by_name("mint_ext", BTreeMap::new());
    let uref: URef = contract_api::new_uref(contract).into();

    contract_api::ret(&uref, &vec![uref]);
}
