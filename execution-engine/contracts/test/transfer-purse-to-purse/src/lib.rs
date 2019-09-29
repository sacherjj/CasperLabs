#![no_std]
#![feature(cell_update)]

#[macro_use]
extern crate alloc;
extern crate contract_ffi;

use alloc::string::String;
use contract_ffi::contract_api::{
    add_uref, create_purse, get_arg, get_balance, get_uref, has_uref, main_purse, new_turef,
    revert, transfer_from_purse_to_purse, Error,
};
use contract_ffi::key::Key;
use contract_ffi::value::account::PurseId;
use contract_ffi::value::U512;

#[no_mangle]
pub extern "C" fn call() {
    let main_purse = main_purse();
    // add or update `main_purse` if it doesn't exist already
    add_uref("purse:main", &Key::from(main_purse.value()));

    let src_purse_name: String = match get_arg(0) {
        Some(Ok(data)) => data,
        Some(Err(_)) => revert(Error::InvalidArgument.into()),
        None => revert(Error::MissingArgument.into()),
    };

    let src_purse_key = get_uref(&src_purse_name).unwrap_or_else(|| revert(103));

    let src_purse = match src_purse_key.as_uref() {
        Some(uref) => PurseId::new(*uref),
        None => revert(104),
    };
    let dst_purse_name: String = match get_arg(1) {
        Some(Ok(data)) => data,
        Some(Err(_)) => revert(Error::InvalidArgument.into()),
        None => revert(Error::MissingArgument.into()),
    };

    let dst_purse = if !has_uref(&dst_purse_name) {
        // If `dst_purse_name` is not in known urefs list then create a new purse
        let purse = create_purse();
        // and save it in known urefs
        add_uref(&dst_purse_name, &purse.value().into());
        purse
    } else {
        let uref_key = get_uref(&dst_purse_name).unwrap_or_else(|| revert(105));
        match uref_key.as_uref() {
            Some(uref) => PurseId::new(*uref),
            None => revert(106),
        }
    };
    let amount: U512 = match get_arg(2) {
        Some(Ok(data)) => data,
        Some(Err(_)) => revert(Error::InvalidArgument.into()),
        None => revert(Error::MissingArgument.into()),
    };

    let transfer_result = transfer_from_purse_to_purse(src_purse, dst_purse, amount);

    // Assert is done here
    let final_balance = get_balance(main_purse).unwrap_or_else(|| revert(107));

    let result = format!("{:?}", transfer_result);
    // Add new urefs
    let result_key: Key = new_turef(result).into();
    add_uref("purse_transfer_result", &result_key);
    add_uref("main_purse_balance", &new_turef(final_balance).into());
}
