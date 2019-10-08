#![no_std]
#![feature(cell_update)]

extern crate alloc;

extern crate contract_ffi;

use contract_ffi::contract_api::{
    add_associated_key, get_arg, revert, set_action_threshold, Error,
};
use contract_ffi::value::account::{ActionType, PublicKey, Weight};

#[no_mangle]
pub extern "C" fn call() {
    add_associated_key(PublicKey::new([123; 32]), Weight::new(254))
        .unwrap_or_else(|_| revert(Error::User(50)));
    let key_management_threshold: Weight = match get_arg(0) {
        Some(Ok(data)) => data,
        Some(Err(_)) => revert(Error::InvalidArgument),
        None => revert(Error::MissingArgument),
    };
    let deployment_threshold: Weight = match get_arg(1) {
        Some(Ok(data)) => data,
        Some(Err(_)) => revert(Error::InvalidArgument),
        None => revert(Error::MissingArgument),
    };

    set_action_threshold(ActionType::KeyManagement, key_management_threshold)
        .unwrap_or_else(|_| revert(Error::User(100)));
    set_action_threshold(ActionType::Deployment, deployment_threshold)
        .unwrap_or_else(|_| revert(Error::User(200)));
}
