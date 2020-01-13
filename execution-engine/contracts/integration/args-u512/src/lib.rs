#![no_std]

use contract::{
    contract_api::{runtime, Error},
    unwrap_or_revert::UnwrapOrRevert,
    value::U512,
};

enum Arg {
    Number = 0,
}

#[no_mangle]
pub extern "C" fn call() {
    let number: U512 = runtime::get_arg(Arg::Number as u32)
        .unwrap_or_revert_with(Error::MissingArgument)
        .unwrap_or_revert_with(Error::InvalidArgument);

    let user_code: u16 = number.as_u32() as u16;
    runtime::revert(Error::User(user_code));
}
