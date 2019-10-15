#![no_std]

extern crate alloc;
extern crate contract_ffi;

use alloc::string::String;

use contract_ffi::contract_api::{account, runtime, Error as ApiError};
use contract_ffi::unwrap_or_revert::UnwrapOrRevert;
use contract_ffi::value::account::{ActionType, PublicKey, RemoveKeyFailure, Weight};

enum Arg {
    Pass = 0,
}

#[repr(u16)]
enum Error {
    AddKey1 = 0,
    AddKey2 = 1,
    SetActionThreshold = 2,
    RemoveMissingKey = 3,
    RemovePermissionDenied = 4,
    RemoveThresholdViolation = 5,
    UnknownPass = 6,
}

impl Into<ApiError> for Error {
    fn into(self) -> ApiError {
        ApiError::User(self as u16)
    }
}

impl From<RemoveKeyFailure> for Error {
    fn from(error: RemoveKeyFailure) -> Error {
        match error {
            RemoveKeyFailure::MissingKey => Error::RemoveMissingKey,
            RemoveKeyFailure::PermissionDenied => Error::RemovePermissionDenied,
            RemoveKeyFailure::ThresholdViolation => Error::RemoveThresholdViolation,
        }
    }
}

#[no_mangle]
pub extern "C" fn call() {
    let pass: String = runtime::get_arg(Arg::Pass as u32).unwrap().unwrap();
    match pass.as_str() {
        "init" => {
            // Deployed with identity key only
            account::add_associated_key(PublicKey::new([100; 32]), Weight::new(2))
                .unwrap_or_else(|_| runtime::revert(Error::AddKey1));
            account::add_associated_key(PublicKey::new([101; 32]), Weight::new(255))
                .unwrap_or_else(|_| runtime::revert(Error::AddKey2));
            account::set_action_threshold(ActionType::KeyManagement, Weight::new(254))
                .unwrap_or_else(|_| runtime::revert(Error::SetActionThreshold));
        }

        "test" => {
            // Deployed with two keys: 1 and 255 (total 256 which overflows to 255) to satify new
            // threshold
            account::remove_associated_key(PublicKey::new([100; 32]))
                .map_err(Error::from)
                .unwrap_or_revert();
        }
        _ => {
            runtime::revert(Error::UnknownPass);
        }
    }
}
