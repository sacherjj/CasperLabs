#![no_std]

extern crate alloc;

use alloc::string::String;

use contract::{
    contract_api::{account, runtime, system},
    unwrap_or_revert::UnwrapOrRevert,
};
use types::{account::PurseId, ApiError, U512};

#[no_mangle]
pub extern "C" fn call() {
    let amount: U512 = runtime::get_arg(1)
        .unwrap_or_revert_with(ApiError::MissingArgument)
        .unwrap_or_revert_with(ApiError::InvalidArgument);

    let destination_name: String = runtime::get_arg(0)
        .unwrap_or_revert_with(ApiError::MissingArgument)
        .unwrap_or_revert_with(ApiError::InvalidArgument);

    let source: PurseId = account::get_main_purse();
    let destination = system::create_purse();
    system::transfer_from_purse_to_purse(source, destination, amount).unwrap_or_revert();
    runtime::put_key(&destination_name, destination.value().into());
}
