#![no_std]
#![feature(cell_update)]

extern crate alloc;
extern crate contract_ffi;

use alloc::string::String;

use contract_ffi::contract_api::{
    add_associated_key, get_arg, remove_associated_key, revert, set_action_threshold,
    update_associated_key, Error,
};
use contract_ffi::value::account::{
    ActionType, AddKeyFailure, PublicKey, RemoveKeyFailure, SetThresholdFailure, UpdateKeyFailure,
    Weight,
};

#[no_mangle]
pub extern "C" fn call() {
    let stage: String = match get_arg(0) {
        Some(Ok(data)) => data,
        Some(Err(_)) => revert(Error::InvalidArgument),
        None => revert(Error::MissingArgument),
    };
    if stage == "init" {
        // executed with weight >= 1
        add_associated_key(PublicKey::new([42; 32]), Weight::new(100))
            .unwrap_or_else(|_| revert(Error::User(100)));
        // this key will be used to test permission denied when removing keys with low
        // total weight
        add_associated_key(PublicKey::new([43; 32]), Weight::new(1))
            .unwrap_or_else(|_| revert(Error::User(101)));
        add_associated_key(PublicKey::new([1; 32]), Weight::new(1))
            .unwrap_or_else(|_| revert(Error::User(102)));
        set_action_threshold(ActionType::KeyManagement, Weight::new(101))
            .unwrap_or_else(|_| revert(Error::User(103)));
    } else if stage == "test-permission-denied" {
        // Has to be executed with keys of total weight < 255
        match add_associated_key(PublicKey::new([44; 32]), Weight::new(1)) {
            Ok(_) => revert(Error::User(200)),
            Err(AddKeyFailure::PermissionDenied) => {}
            Err(_) => revert(Error::User(201)),
        }

        match update_associated_key(PublicKey::new([43; 32]), Weight::new(2)) {
            Ok(_) => revert(Error::User(300)),
            Err(UpdateKeyFailure::PermissionDenied) => {}
            Err(_) => revert(Error::User(301)),
        }
        match remove_associated_key(PublicKey::new([43; 32])) {
            Ok(_) => revert(Error::User(400)),
            Err(RemoveKeyFailure::PermissionDenied) => {}
            Err(_) => revert(Error::User(401)),
        }

        match set_action_threshold(ActionType::KeyManagement, Weight::new(255)) {
            Ok(_) => revert(Error::User(500)),
            Err(SetThresholdFailure::PermissionDeniedError) => {}
            Err(_) => revert(Error::User(501)),
        }
    } else if stage == "test-key-mgmnt-succeed" {
        // Has to be executed with keys of total weight >= 254
        add_associated_key(PublicKey::new([44; 32]), Weight::new(1))
            .unwrap_or_else(|_| revert(Error::User(600)));
        // Updates [43;32] key weight created in init stage
        update_associated_key(PublicKey::new([44; 32]), Weight::new(2))
            .unwrap_or_else(|_| revert(Error::User(601)));
        // Removes [43;32] key created in init stage
        remove_associated_key(PublicKey::new([44; 32]))
            .unwrap_or_else(|_| revert(Error::User(602)));
        // Sets action threshodl
        set_action_threshold(ActionType::KeyManagement, Weight::new(100))
            .unwrap_or_else(|_| revert(Error::User(603)));
    } else {
        revert(Error::User(1))
    }
}
