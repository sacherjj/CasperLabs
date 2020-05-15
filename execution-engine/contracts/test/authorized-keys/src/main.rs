#![no_std]
#![no_main]

use contract::{
    contract_api::{account, runtime},
    unwrap_or_revert::UnwrapOrRevert,
};
use types::{
    account::{ActionType, AddKeyFailure, PublicKey, Weight},
    ApiError,
};

#[no_mangle]
pub extern "C" fn call() {
    match account::add_associated_key(PublicKey::ed25519_from([123; 32]), Weight::new(100)) {
        Err(AddKeyFailure::DuplicateKey) => {}
        Err(_) => runtime::revert(ApiError::User(50)),
        Ok(_) => {}
    };

    let key_management_threshold: Weight = runtime::get_named_arg("key_management_threshold");
    let deploy_threshold: Weight = runtime::get_named_arg("deploy_threshold");

    if key_management_threshold != Weight::new(0) {
        account::set_action_threshold(ActionType::KeyManagement, key_management_threshold)
            .unwrap_or_revert()
    }

    if deploy_threshold != Weight::new(0) {
        account::set_action_threshold(ActionType::Deployment, deploy_threshold).unwrap_or_revert()
    }
}
