#![no_std]

extern crate contract_ffi;

use contract_ffi::contract_api::{account, runtime, Error};
use contract_ffi::unwrap_or_revert::UnwrapOrRevert;
use contract_ffi::value::account::{ActionType, Weight};

#[no_mangle]
pub extern "C" fn call() {
    let km_weight: u32 = runtime::get_arg(0)
        .unwrap_or_revert_with(Error::MissingArgument)
        .unwrap_or_revert_with(Error::InvalidArgument);
    let dep_weight: u32 = runtime::get_arg(1)
        .unwrap_or_revert_with(Error::MissingArgument)
        .unwrap_or_revert_with(Error::InvalidArgument);
    let key_management_threshold = Weight::new(km_weight as u8);
    let deploy_threshold = Weight::new(dep_weight as u8);

    if key_management_threshold != Weight::new(0) {
        account::set_action_threshold(ActionType::KeyManagement, key_management_threshold)
            .unwrap_or_revert_with(Error::User(100));
    }

    if deploy_threshold != Weight::new(0) {
        account::set_action_threshold(ActionType::Deployment, deploy_threshold)
            .unwrap_or_revert_with(Error::User(200));
    }
}
