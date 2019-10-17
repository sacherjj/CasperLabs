use alloc::vec::Vec;
use core::convert::TryFrom;

use super::to_ptr;
use crate::bytesrepr::deserialize;
use crate::contract_api::alloc_bytes;
use crate::ext_ffi;
pub use crate::value::account::PublicKey;
use crate::value::account::{
    ActionType, AddKeyFailure, PurseId, RemoveKeyFailure, SetThresholdFailure, UpdateKeyFailure,
    Weight, PURSE_ID_SIZE_SERIALIZED,
};

pub fn get_main_purse() -> PurseId {
    let dest_ptr = alloc_bytes(PURSE_ID_SIZE_SERIALIZED);
    let bytes = unsafe {
        ext_ffi::get_main_purse(dest_ptr);
        Vec::from_raw_parts(dest_ptr, PURSE_ID_SIZE_SERIALIZED, PURSE_ID_SIZE_SERIALIZED)
    };
    deserialize(&bytes).unwrap()
}

pub fn set_action_threshold(
    permission_level: ActionType,
    threshold: Weight,
) -> Result<(), SetThresholdFailure> {
    let permission_level = permission_level as u32;
    let threshold = threshold.value().into();
    let result = unsafe { ext_ffi::set_action_threshold(permission_level, threshold) };
    if result == 0 {
        Ok(())
    } else {
        Err(SetThresholdFailure::try_from(result).expect("invalid result"))
    }
}

/// Adds a public key with associated weight to an account.
pub fn add_associated_key(public_key: PublicKey, weight: Weight) -> Result<(), AddKeyFailure> {
    let (public_key_ptr, _public_key_size, _bytes) = to_ptr(&public_key);
    // Cast of u8 (weight) into i32 is assumed to be always safe
    let result = unsafe { ext_ffi::add_associated_key(public_key_ptr, weight.value().into()) };
    if result == 0 {
        Ok(())
    } else {
        Err(AddKeyFailure::try_from(result).expect("invalid result"))
    }
}

/// Removes a public key from associated keys on an account
pub fn remove_associated_key(public_key: PublicKey) -> Result<(), RemoveKeyFailure> {
    let (public_key_ptr, _public_key_size, _bytes) = to_ptr(&public_key);
    let result = unsafe { ext_ffi::remove_associated_key(public_key_ptr) };
    if result == 0 {
        Ok(())
    } else {
        Err(RemoveKeyFailure::try_from(result).expect("invalid result"))
    }
}

/// Updates the value stored under a public key associated with an account
pub fn update_associated_key(
    public_key: PublicKey,
    weight: Weight,
) -> Result<(), UpdateKeyFailure> {
    let (public_key_ptr, _public_key_size, _bytes) = to_ptr(&public_key);
    // Cast of u8 (weight) into i32 is assumed to be always safe
    let result = unsafe { ext_ffi::update_associated_key(public_key_ptr, weight.value().into()) };
    if result == 0 {
        Ok(())
    } else {
        Err(UpdateKeyFailure::try_from(result).expect("invalid result"))
    }
}
