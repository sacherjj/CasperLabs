#![no_std]
#![feature(alloc, cell_update)]

extern crate alloc;
extern crate cl_std;

use cl_std::contract_api::{
    add_associated_key, remove_associated_key, revert, set_action_threshold, update_associated_key,
};
use cl_std::value::account::{
    ActionType, PublicKey, RemoveKeyFailure, SetThresholdFailure, UpdateKeyFailure, Weight,
};

#[no_mangle]
pub extern "C" fn call() {
    // Starts with deployment=1, key_management=1
    let key_1 = PublicKey::new([42; 32]);
    let key_2 = PublicKey::new([43; 32]);

    // Total keys weight = 11 (identity + new key's weight)
    add_associated_key(key_1, Weight::new(10)).unwrap_or_else(|_| revert(100));
    add_associated_key(key_2, Weight::new(11)).unwrap_or_else(|_| revert(101));

    set_action_threshold(ActionType::KeyManagement, Weight::new(13))
        .unwrap_or_else(|_| revert(200));
    set_action_threshold(ActionType::Deployment, Weight::new(10)).unwrap_or_else(|_| revert(201));

    match remove_associated_key(key_2) {
        Err(RemoveKeyFailure::ThresholdViolation) => {
            // Shouldn't be able to remove key because key threshold == 11 and removing would violate the constraint
        }
        Err(_) => revert(300),
        Ok(_) => revert(301),
    }

    match set_action_threshold(ActionType::KeyManagement, Weight::new(255)) {
        Err(SetThresholdFailure::InsufficientTotalWeight) => {
            // Changing key management threshold to this value would lock down account for future operations
        }
        Err(_) => revert(400),
        Ok(_) => revert(401),
    }
    // Key management threshold is 11, so changing threshold of key from 10 to 11 would violate
    match update_associated_key(key_2, Weight::new(1)) {
        Err(UpdateKeyFailure::ThresholdViolation) => {
            // Changing it would mean the total weight would be identity(1) + key_1(10) + key_2(1) < key_mgmt(13)
        }
        Err(_) => revert(500),
        Ok(_) => revert(501),
    }
}
