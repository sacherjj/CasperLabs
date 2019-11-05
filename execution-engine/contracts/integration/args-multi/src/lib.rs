#![no_std]

use contract_ffi::contract_api::{runtime, Error};
use contract_ffi::unwrap_or_revert::UnwrapOrRevert;

enum Arg {
    AccountNumber = 0,
    Number = 1,
}

#[no_mangle]
pub extern "C" fn call() {
    let account_number: [u8; 32] = runtime::get_arg(Arg::AccountNumber as u32)
        .unwrap_or_revert_with(Error::MissingArgument)
        .unwrap_or_revert_with(Error::InvalidArgument);
    let number: u32 = runtime::get_arg(Arg::Number as u32)
        .unwrap_or_revert_with(Error::MissingArgument)
        .unwrap_or_revert_with(Error::InvalidArgument);

    let account_sum: u8 = account_number.iter().sum();
    let total_sum: u32 = u32::from(account_sum) + number;

    runtime::revert(Error::User(total_sum as u16));
}
