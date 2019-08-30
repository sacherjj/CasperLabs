#![no_std]
#![feature(cell_update)]

extern crate alloc;

extern crate contract_ffi;

use contract_ffi::contract_api::{add_associated_key, get_arg, revert, set_action_threshold};
use contract_ffi::value::account::{ActionType, PublicKey, Weight};

#[no_mangle]
pub extern "C" fn call() {
    add_associated_key(PublicKey::new([123; 32]), Weight::new(254)).unwrap_or_else(|_| revert(50));
    let key_management_threshold: Weight = get_arg(0);
    let deployment_threshold: Weight = get_arg(1);

    set_action_threshold(ActionType::KeyManagement, key_management_threshold)
        .unwrap_or_else(|_| revert(100));
    set_action_threshold(ActionType::Deployment, deployment_threshold)
        .unwrap_or_else(|_| revert(200));
}
