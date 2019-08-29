#![no_std]
#![feature(cell_update)]

extern crate alloc;
extern crate contract_ffi;

use contract_ffi::contract_api;
use contract_ffi::value::account::PurseId;

#[no_mangle]
pub extern "C" fn call() {
    let known_main_purse: PurseId = contract_api::get_arg(0);
    let main_purse: PurseId = contract_api::main_purse();
    assert_eq!(
        main_purse, known_main_purse,
        "main purse was not known purse"
    );
}
