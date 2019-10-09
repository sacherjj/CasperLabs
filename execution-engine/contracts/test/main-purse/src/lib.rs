#![no_std]

extern crate contract_ffi;

use contract_ffi::contract_api::{self, Error};
use contract_ffi::unwrap_or_revert::UnwrapOrRevert;
use contract_ffi::value::account::PurseId;

#[no_mangle]
pub extern "C" fn call() {
    let known_main_purse: PurseId = contract_api::runtime::get_arg(0)
        .unwrap_or_revert_with(Error::MissingArgument)
        .unwrap_or_revert_with(Error::InvalidArgument);
    let main_purse: PurseId = contract_api::account::get_main_purse();
    assert_eq!(
        main_purse, known_main_purse,
        "main purse was not known purse"
    );
}
