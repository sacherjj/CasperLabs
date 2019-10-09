use super::runtime::get_caller;
use super::storage::read_untyped;
use crate::contract_api::alloc_util::to_ptr;
use crate::ext_ffi;
use crate::key::Key;
pub use crate::value::account::PublicKey;
use crate::value::account::{
    ActionType, AddKeyFailure, PurseId, RemoveKeyFailure, SetThresholdFailure, UpdateKeyFailure,
    Weight,
};
use crate::value::Account;
use core::convert::{TryFrom, TryInto};

pub fn get_main_purse() -> PurseId {
    // TODO: this could be more efficient, bringing the entire account
    // object across the host/wasm boundary only to use 32 bytes of
    // its data is pretty bad. A native FFI (as opposed to a library
    // API) would get around this problem. However, this solution
    // works for the time being.
    // https://casperlabs.atlassian.net/browse/EE-439
    let account_pk = get_caller();
    let key = Key::Account(account_pk.value());
    let account: Account = read_untyped(&key).unwrap().unwrap().try_into().unwrap();
    account.purse_id()
}

pub fn set_action_threshold(
    permission_level: ActionType,
    threshold: Weight,
) -> Result<(), SetThresholdFailure> {
    let permission_level = permission_level as u32;
    let threshold = threshold.value().into();
    let result = unsafe { ext_ffi::set_action_threshold(permission_level, threshold) };
    match result {
        d if d == 0 => Ok(()),
        d => Err(SetThresholdFailure::try_from(d).expect("invalid result")),
    }
}

/// Adds a public key with associated weight to an account.
pub fn add_associated_key(public_key: PublicKey, weight: Weight) -> Result<(), AddKeyFailure> {
    let (public_key_ptr, _public_key_size, _bytes) = to_ptr(&public_key);
    // Cast of u8 (weight) into i32 is assumed to be always safe
    let result = unsafe { ext_ffi::add_associated_key(public_key_ptr, weight.value().into()) };
    // Translates FFI
    match result {
        d if d == 0 => Ok(()),
        d => Err(AddKeyFailure::try_from(d).expect("invalid result")),
    }
}

/// Removes a public key from associated keys on an account
pub fn remove_associated_key(public_key: PublicKey) -> Result<(), RemoveKeyFailure> {
    let (public_key_ptr, _public_key_size, _bytes) = to_ptr(&public_key);
    let result = unsafe { ext_ffi::remove_associated_key(public_key_ptr) };
    match result {
        d if d == 0 => Ok(()),
        d => Err(RemoveKeyFailure::try_from(d).expect("invalid result")),
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
    // Translates FFI
    match result {
        d if d == 0 => Ok(()),
        d => Err(UpdateKeyFailure::try_from(d).expect("invalid result")),
    }
}
