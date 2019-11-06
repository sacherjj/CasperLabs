#![no_std]

use contract_ffi::{
    contract_api::{runtime, Error},
    unwrap_or_revert::UnwrapOrRevert,
};

enum Arg {
    Number = 0,
}

#[no_mangle]
pub extern "C" fn call() {
    let number: u32 = runtime::get_arg(Arg::Number as u32)
        .unwrap_or_revert_with(Error::MissingArgument)
        .unwrap_or_revert_with(Error::InvalidArgument);
    runtime::revert(Error::User(number as u16));
}
