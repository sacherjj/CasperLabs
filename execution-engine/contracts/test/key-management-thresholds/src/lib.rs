#![no_std]

extern crate alloc;

extern crate contract_ffi;

use alloc::string::String;

use contract_ffi::contract_api::{self, Error};
use contract_ffi::unwrap_or_revert::UnwrapOrRevert;
use contract_ffi::value::account::{
    ActionType, AddKeyFailure, PublicKey, RemoveKeyFailure, SetThresholdFailure, UpdateKeyFailure,
    Weight,
};

#[no_mangle]
pub extern "C" fn call() {
    let stage: String = contract_api::runtime::get_arg(0)
        .unwrap_or_revert_with(Error::MissingArgument)
        .unwrap_or_revert_with(Error::InvalidArgument);

    if stage == "init" {
        // executed with weight >= 1
        contract_api::account::add_associated_key(PublicKey::new([42; 32]), Weight::new(100))
            .unwrap_or_else(|_| contract_api::runtime::revert(Error::User(100)));
        // this key will be used to test permission denied when removing keys with low
        // total weight
        contract_api::account::add_associated_key(PublicKey::new([43; 32]), Weight::new(1))
            .unwrap_or_else(|_| contract_api::runtime::revert(Error::User(101)));
        contract_api::account::add_associated_key(PublicKey::new([1; 32]), Weight::new(1))
            .unwrap_or_else(|_| contract_api::runtime::revert(Error::User(102)));
        contract_api::account::set_action_threshold(ActionType::KeyManagement, Weight::new(101))
            .unwrap_or_else(|_| contract_api::runtime::revert(Error::User(103)));
    } else if stage == "test-permission-denied" {
        // Has to be executed with keys of total weight < 255
        match contract_api::account::add_associated_key(PublicKey::new([44; 32]), Weight::new(1)) {
            Ok(_) => contract_api::runtime::revert(Error::User(200)),
            Err(AddKeyFailure::PermissionDenied) => {}
            Err(_) => contract_api::runtime::revert(Error::User(201)),
        }

        match contract_api::account::update_associated_key(PublicKey::new([43; 32]), Weight::new(2))
        {
            Ok(_) => contract_api::runtime::revert(Error::User(300)),
            Err(UpdateKeyFailure::PermissionDenied) => {}
            Err(_) => contract_api::runtime::revert(Error::User(301)),
        }
        match contract_api::account::remove_associated_key(PublicKey::new([43; 32])) {
            Ok(_) => contract_api::runtime::revert(Error::User(400)),
            Err(RemoveKeyFailure::PermissionDenied) => {}
            Err(_) => contract_api::runtime::revert(Error::User(401)),
        }

        match contract_api::account::set_action_threshold(
            ActionType::KeyManagement,
            Weight::new(255),
        ) {
            Ok(_) => contract_api::runtime::revert(Error::User(500)),
            Err(SetThresholdFailure::PermissionDeniedError) => {}
            Err(_) => contract_api::runtime::revert(Error::User(501)),
        }
    } else if stage == "test-key-mgmnt-succeed" {
        // Has to be executed with keys of total weight >= 254
        contract_api::account::add_associated_key(PublicKey::new([44; 32]), Weight::new(1))
            .unwrap_or_else(|_| contract_api::runtime::revert(Error::User(600)));
        // Updates [43;32] key weight created in init stage
        contract_api::account::update_associated_key(PublicKey::new([44; 32]), Weight::new(2))
            .unwrap_or_else(|_| contract_api::runtime::revert(Error::User(601)));
        // Removes [43;32] key created in init stage
        contract_api::account::remove_associated_key(PublicKey::new([44; 32]))
            .unwrap_or_else(|_| contract_api::runtime::revert(Error::User(602)));
        // Sets action threshodl
        contract_api::account::set_action_threshold(ActionType::KeyManagement, Weight::new(100))
            .unwrap_or_else(|_| contract_api::runtime::revert(Error::User(603)));
    } else {
        contract_api::runtime::revert(Error::User(1))
    }
}
