#![no_std]

extern crate contract_ffi;

use contract_ffi::contract_api::{self, Error};
use contract_ffi::unwrap_or_revert::UnwrapOrRevert;
use contract_ffi::value::account::{ActionType, AddKeyFailure, PublicKey, Weight};

#[no_mangle]
pub extern "C" fn call() {
    match contract_api::add_associated_key(PublicKey::new([123; 32]), Weight::new(100)) {
        Err(AddKeyFailure::DuplicateKey) => {}
        Err(_) => contract_api::revert(Error::User(50)),
        Ok(_) => {}
    };

    let key_management_threshold: Weight = contract_api::get_arg(0)
        .unwrap_or_revert_with(Error::MissingArgument)
        .unwrap_or_revert_with(Error::InvalidArgument);
    let deploy_threshold: Weight = contract_api::get_arg(1)
        .unwrap_or_revert_with(Error::MissingArgument)
        .unwrap_or_revert_with(Error::InvalidArgument);

    if key_management_threshold != Weight::new(0) {
        contract_api::set_action_threshold(ActionType::KeyManagement, key_management_threshold)
            .unwrap_or_else(|_| contract_api::revert(Error::User(100)));
    }

    if deploy_threshold != Weight::new(0) {
        contract_api::set_action_threshold(ActionType::Deployment, deploy_threshold)
            .unwrap_or_else(|_| contract_api::revert(Error::User(200)));
    }
}
