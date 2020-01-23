#![no_std]

use contract::{
    contract_api::{account, runtime},
    unwrap_or_revert::UnwrapOrRevert,
};
use types::{
    account::{ActionType, PublicKey, Weight},
    ApiError,
};

#[no_mangle]
pub extern "C" fn call() {
    account::add_associated_key(PublicKey::new([123; 32]), Weight::new(254)).unwrap_or_revert();
    let key_management_threshold: Weight = runtime::get_arg(0)
        .unwrap_or_revert_with(ApiError::MissingArgument)
        .unwrap_or_revert_with(ApiError::InvalidArgument);

    let deployment_threshold: Weight = runtime::get_arg(1)
        .unwrap_or_revert_with(ApiError::MissingArgument)
        .unwrap_or_revert_with(ApiError::InvalidArgument);

    account::set_action_threshold(ActionType::KeyManagement, key_management_threshold)
        .unwrap_or_revert();
    account::set_action_threshold(ActionType::Deployment, deployment_threshold).unwrap_or_revert();
}
