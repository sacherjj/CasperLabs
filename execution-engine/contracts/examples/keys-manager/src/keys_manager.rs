use contract::{contract_api::account, unwrap_or_revert::UnwrapOrRevert};

use types::account::{
    ActionType, AddKeyFailure, PublicKey, RemoveKeyFailure, SetThresholdFailure, UpdateKeyFailure,
    Weight,
};

use crate::{api::Api, error::Error};

fn add_or_update_key(key: PublicKey, weight: Weight) -> Result<(), Error> {
    match account::update_associated_key(key, weight) {
        Ok(()) => Ok(()),
        Err(UpdateKeyFailure::MissingKey) => add_key(key, weight),
        Err(UpdateKeyFailure::PermissionDenied) => Err(Error::PermissionDenied),
        Err(UpdateKeyFailure::ThresholdViolation) => Err(Error::ThresholdViolation),
    }
}

fn add_key(key: PublicKey, weight: Weight) -> Result<(), Error> {
    match account::add_associated_key(key, weight) {
        Ok(()) => Ok(()),
        Err(AddKeyFailure::MaxKeysLimit) => Err(Error::MaxKeysLimit),
        Err(AddKeyFailure::DuplicateKey) => Err(Error::DuplicateKey), // Should never happen.
        Err(AddKeyFailure::PermissionDenied) => Err(Error::PermissionDenied),
    }
}

fn remove_key_if_exists(key: PublicKey) -> Result<(), Error> {
    match account::remove_associated_key(key) {
        Ok(()) | Err(RemoveKeyFailure::MissingKey) => Ok(()),
        Err(RemoveKeyFailure::PermissionDenied) => Err(Error::PermissionDenied),
        Err(RemoveKeyFailure::ThresholdViolation) => Err(Error::ThresholdViolation),
    }
}

fn set_threshold(permission_level: ActionType, threshold: Weight) -> Result<(), Error> {
    match account::set_action_threshold(permission_level, threshold) {
        Ok(()) => Ok(()),
        Err(SetThresholdFailure::KeyManagementThresholdError) => {
            Err(Error::KeyManagementThresholdError)
        }
        Err(SetThresholdFailure::DeploymentThresholdError) => Err(Error::DeploymentThresholdError),
        Err(SetThresholdFailure::PermissionDeniedError) => Err(Error::PermissionDenied),
        Err(SetThresholdFailure::InsufficientTotalWeight) => Err(Error::InsufficientTotalWeight),
    }
}

pub fn execute() {
    let result = match Api::from_args() {
        Api::SetKeyWeight(key, weight) => {
            if weight.value() == 0 {
                remove_key_if_exists(key)
            } else {
                add_or_update_key(key, weight)
            }
        }
        Api::SetDeploymentThreshold(threshold) => set_threshold(ActionType::Deployment, threshold),
        Api::SetKeyManagementThreshold(threshold) => {
            set_threshold(ActionType::KeyManagement, threshold)
        }
    };
    result.unwrap_or_revert()
}
