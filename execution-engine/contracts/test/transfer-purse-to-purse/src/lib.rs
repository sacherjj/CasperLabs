#![no_std]

#[macro_use]
extern crate alloc;

extern crate contract_ffi;

use alloc::string::String;

use contract_ffi::contract_api::{self, Error};
use contract_ffi::key::Key;
use contract_ffi::unwrap_or_revert::UnwrapOrRevert;
use contract_ffi::value::account::PurseId;
use contract_ffi::value::U512;

#[no_mangle]
pub extern "C" fn call() {
    let main_purse = contract_api::account::get_main_purse();
    // add or update `main_purse` if it doesn't exist already
    contract_api::runtime::put_key("purse:main", &Key::from(main_purse.value()));

    let src_purse_name: String = contract_api::runtime::get_arg(0)
        .unwrap_or_revert_with(Error::MissingArgument)
        .unwrap_or_revert_with(Error::InvalidArgument);

    let src_purse_key =
        contract_api::runtime::get_key(&src_purse_name).unwrap_or_revert_with(Error::User(103));

    let src_purse = match src_purse_key.as_uref() {
        Some(uref) => PurseId::new(*uref),
        None => contract_api::runtime::revert(Error::User(104)),
    };
    let dst_purse_name: String = contract_api::runtime::get_arg(1)
        .unwrap_or_revert_with(Error::MissingArgument)
        .unwrap_or_revert_with(Error::InvalidArgument);

    let dst_purse = if !contract_api::runtime::has_key(&dst_purse_name) {
        // If `dst_purse_name` is not in known urefs list then create a new purse
        let purse = contract_api::system::create_purse();
        // and save it in known urefs
        contract_api::runtime::put_key(&dst_purse_name, &purse.value().into());
        purse
    } else {
        let uref_key =
            contract_api::runtime::get_key(&dst_purse_name).unwrap_or_revert_with(Error::User(105));
        match uref_key.as_uref() {
            Some(uref) => PurseId::new(*uref),
            None => contract_api::runtime::revert(Error::User(106)),
        }
    };
    let amount: U512 = contract_api::runtime::get_arg(2)
        .unwrap_or_revert_with(Error::MissingArgument)
        .unwrap_or_revert_with(Error::InvalidArgument);

    let transfer_result =
        contract_api::system::transfer_from_purse_to_purse(src_purse, dst_purse, amount);

    // Assert is done here
    let final_balance = contract_api::system::get_balance(main_purse)
        .unwrap_or_else(|| contract_api::runtime::revert(Error::User(107)));

    let result = format!("{:?}", transfer_result);
    // Add new urefs
    let result_key: Key = contract_api::storage::new_turef(result).into();
    contract_api::runtime::put_key("purse_transfer_result", &result_key);
    contract_api::runtime::put_key(
        "main_purse_balance",
        &contract_api::storage::new_turef(final_balance).into(),
    );
}
