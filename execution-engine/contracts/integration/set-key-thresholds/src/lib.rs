#![no_std]

use contract::{
    contract_api::{account, runtime},
    unwrap_or_revert::UnwrapOrRevert,
};
use types::{
    account::{ActionType, Weight},
    ApiError,
};

enum Arg {
    KeyManagement = 0,
    Deploy,
}

#[repr(u16)]
enum Error {
    SetKeyManagementThreshold = 100,
    SetDeploymentThreshold = 200,
}

impl Into<ApiError> for Error {
    fn into(self) -> ApiError {
        ApiError::User(self as u16)
    }
}

#[no_mangle]
pub extern "C" fn call() {
    let km_weight: u32 = runtime::get_arg(Arg::KeyManagement as u32)
        .unwrap_or_revert_with(ApiError::MissingArgument)
        .unwrap_or_revert_with(ApiError::InvalidArgument);
    let dep_weight: u32 = runtime::get_arg(Arg::Deploy as u32)
        .unwrap_or_revert_with(ApiError::MissingArgument)
        .unwrap_or_revert_with(ApiError::InvalidArgument);
    let key_management_threshold = Weight::new(km_weight as u8);
    let deploy_threshold = Weight::new(dep_weight as u8);

    if key_management_threshold != Weight::new(0) {
        account::set_action_threshold(ActionType::KeyManagement, key_management_threshold)
            .unwrap_or_revert_with(Error::SetKeyManagementThreshold);
    }

    if deploy_threshold != Weight::new(0) {
        account::set_action_threshold(ActionType::Deployment, deploy_threshold)
            .unwrap_or_revert_with(Error::SetDeploymentThreshold);
    }
}
