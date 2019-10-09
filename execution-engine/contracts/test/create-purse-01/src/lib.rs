#![no_std]
extern crate alloc;

extern crate contract_ffi;

use alloc::string::String;

use contract_ffi::contract_api::{self, Error};
use contract_ffi::unwrap_or_revert::UnwrapOrRevert;

#[repr(u32)]
enum Args {
    PurseName = 0,
}

pub fn delegate() {
    let purse_name: String = contract_api::get_arg(Args::PurseName as u32)
        .unwrap_or_revert_with(Error::MissingArgument)
        .unwrap_or_revert_with(Error::InvalidArgument);
    let purse_id = contract_api::create_purse();
    contract_api::put_key(&purse_name, &purse_id.value().into());
}

#[cfg(not(feature = "lib"))]
#[no_mangle]
pub extern "C" fn call() {
    delegate()
}
