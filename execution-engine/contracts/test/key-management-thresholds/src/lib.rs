#![no_std]
#![feature(alloc, cell_update)]

extern crate alloc;
extern crate cl_std;

use alloc::string::String;

use cl_std::contract_api::{
    add_associated_key, get_arg, remove_associated_key, revert, set_action_threshold,
    update_associated_key,
};
use cl_std::value::account::{
    ActionType, AddKeyFailure, PublicKey, RemoveKeyFailure, SetThresholdFailure, UpdateKeyFailure,
    Weight,
};

#[no_mangle]
pub extern "C" fn call() {
    let stage: String = get_arg(0);
    if stage == "init" {
        // executed with weight >= 1
        add_associated_key(PublicKey::new([42; 32]), Weight::new(100))
            .unwrap_or_else(|_| revert(100));
        // this key will be used to test permission denied when removing keys with low total weight
        add_associated_key(PublicKey::new([43; 32]), Weight::new(1))
            .unwrap_or_else(|_| revert(101));
        add_associated_key(PublicKey::new([1; 32]), Weight::new(1)).unwrap_or_else(|_| revert(102));
        set_action_threshold(ActionType::KeyManagement, Weight::new(101))
            .unwrap_or_else(|_| revert(103));
    } else if stage == "test-permission-denied" {
        // Has to be executed with keys of total weight < 255
        match add_associated_key(PublicKey::new([44; 32]), Weight::new(1)) {
            Ok(_) => revert(200),
            Err(AddKeyFailure::PermissionDenied) => {}
            Err(_) => revert(201),
        }

        match update_associated_key(PublicKey::new([43; 32]), Weight::new(2)) {
            Ok(_) => revert(300),
            Err(UpdateKeyFailure::PermissionDenied) => {}
            Err(_) => revert(301),
        }
        match remove_associated_key(PublicKey::new([43; 32])) {
            Ok(_) => revert(400),
            Err(RemoveKeyFailure::PermissionDenied) => {}
            Err(_) => revert(401),
        }

        match set_action_threshold(ActionType::KeyManagement, Weight::new(255)) {
            Ok(_) => revert(500),
            Err(SetThresholdFailure::PermissionDeniedError) => {}
            Err(_) => revert(501),
        }
    } else if stage == "test-key-mgmnt-succeed" {
        // Has to be executed with keys of total weight >= 254
        add_associated_key(PublicKey::new([44; 32]), Weight::new(1))
            .unwrap_or_else(|_| revert(600));
        // Updates [43;32] key weight created in init stage
        update_associated_key(PublicKey::new([44; 32]), Weight::new(2))
            .unwrap_or_else(|_| revert(601));
        // Removes [43;32] key created in init stage
        remove_associated_key(PublicKey::new([44; 32])).unwrap_or_else(|_| revert(602));
        // Sets action threshodl
        set_action_threshold(ActionType::KeyManagement, Weight::new(100))
            .unwrap_or_else(|_| revert(603));
    } else {
        revert(1)
    }
}
