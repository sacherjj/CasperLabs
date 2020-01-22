#![no_std]

extern crate alloc;

use alloc::{format, string::String};

use contract::{
    contract_api::{account, runtime, storage, system},
    unwrap_or_revert::UnwrapOrRevert,
};
use types::{account::PurseId, ApiError, Key, U512};

#[no_mangle]
pub extern "C" fn call() {
    let main_purse = account::get_main_purse();
    // add or update `main_purse` if it doesn't exist already
    runtime::put_key("purse:main", Key::from(main_purse.value()));

    let src_purse_name: String = runtime::get_arg(0)
        .unwrap_or_revert_with(ApiError::MissingArgument)
        .unwrap_or_revert_with(ApiError::InvalidArgument);

    let src_purse_key =
        runtime::get_key(&src_purse_name).unwrap_or_revert_with(ApiError::User(103));

    let src_purse = match src_purse_key.as_uref() {
        Some(uref) => PurseId::new(*uref),
        None => runtime::revert(ApiError::User(104)),
    };
    let dst_purse_name: String = runtime::get_arg(1)
        .unwrap_or_revert_with(ApiError::MissingArgument)
        .unwrap_or_revert_with(ApiError::InvalidArgument);

    let dst_purse = if !runtime::has_key(&dst_purse_name) {
        // If `dst_purse_name` is not in known urefs list then create a new purse
        let purse = system::create_purse();
        // and save it in known urefs
        runtime::put_key(&dst_purse_name, purse.value().into());
        purse
    } else {
        let uref_key = runtime::get_key(&dst_purse_name).unwrap_or_revert_with(ApiError::User(105));
        match uref_key.as_uref() {
            Some(uref) => PurseId::new(*uref),
            None => runtime::revert(ApiError::User(106)),
        }
    };
    let amount: U512 = runtime::get_arg(2)
        .unwrap_or_revert_with(ApiError::MissingArgument)
        .unwrap_or_revert_with(ApiError::InvalidArgument);

    let transfer_result = system::transfer_from_purse_to_purse(src_purse, dst_purse, amount);

    // Assert is done here
    let final_balance = system::get_balance(main_purse).unwrap_or_revert_with(ApiError::User(107));

    let result = format!("{:?}", transfer_result);
    // Add new urefs
    let result_key: Key = storage::new_turef(result).into();
    runtime::put_key("purse_transfer_result", result_key);
    runtime::put_key(
        "main_purse_balance",
        storage::new_turef(final_balance).into(),
    );
}
