//! Contains types and constants associated with user accounts.

use alloc::{boxed::Box, vec::Vec};
use core::{
    convert::TryFrom,
    fmt::{Debug, Display, Formatter},
};

use failure::Fail;
use hex_fmt::HexFmt;

use crate::{
    bytesrepr::{Error, FromBytes, ToBytes, U8_SERIALIZED_LENGTH},
    CLType, CLTyped,
};

// This error type is not intended to be used by third party crates.
#[doc(hidden)]
#[derive(Debug, Eq, PartialEq)]
pub struct TryFromIntError(());

/// Associated error type of `TryFrom<&[u8]>` for [`PublicKey`].
#[derive(Debug)]
pub struct TryFromSliceForPublicKeyError(());

/// The various types of action which can be performed in the context of a given account.
#[repr(u32)]
pub enum ActionType {
    /// Represents performing a deploy.
    Deployment = 0,
    /// Represents changing the associated keys (i.e. map of [`PublicKey`]s to [`Weight`]s) or
    /// action thresholds (i.e. the total [`Weight`]s of signing [`PublicKey`]s required to perform
    /// various actions).
    KeyManagement = 1,
}

// This conversion is not intended to be used by third party crates.
#[doc(hidden)]
impl TryFrom<u32> for ActionType {
    type Error = TryFromIntError;

    fn try_from(value: u32) -> Result<Self, Self::Error> {
        // This doesn't use `num_derive` traits such as FromPrimitive and ToPrimitive
        // that helps to automatically create `from_u32` and `to_u32`. This approach
        // gives better control over generated code.
        match value {
            d if d == ActionType::Deployment as u32 => Ok(ActionType::Deployment),
            d if d == ActionType::KeyManagement as u32 => Ok(ActionType::KeyManagement),
            _ => Err(TryFromIntError(())),
        }
    }
}

/// Errors that can occur while changing action thresholds (i.e. the total [`Weight`]s of signing
/// [`PublicKey`]s required to perform various actions) on an account.
#[repr(i32)]
#[derive(Debug, Fail, PartialEq, Eq, Copy, Clone)]
pub enum SetThresholdFailure {
    /// Setting the key-management threshold to a value lower than the deployment threshold is
    /// disallowed.
    #[fail(display = "New threshold should be greater than or equal to deployment threshold")]
    KeyManagementThreshold = 1,
    /// Setting the deployment threshold to a value greater than any other threshold is disallowed.
    #[fail(display = "New threshold should be lower than or equal to key management threshold")]
    DeploymentThreshold = 2,
    /// Caller doesn't have sufficient permissions to set new thresholds.
    #[fail(display = "Unable to set action threshold due to insufficient permissions")]
    PermissionDeniedError = 3,
    /// Setting a threshold to a value greater than the total weight of associated keys is
    /// disallowed.
    #[fail(
        display = "New threshold should be lower or equal than total weight of associated keys"
    )]
    InsufficientTotalWeight = 4,
}

// This conversion is not intended to be used by third party crates.
#[doc(hidden)]
impl TryFrom<i32> for SetThresholdFailure {
    type Error = TryFromIntError;

    fn try_from(value: i32) -> Result<Self, Self::Error> {
        match value {
            d if d == SetThresholdFailure::KeyManagementThreshold as i32 => {
                Ok(SetThresholdFailure::KeyManagementThreshold)
            }
            d if d == SetThresholdFailure::DeploymentThreshold as i32 => {
                Ok(SetThresholdFailure::DeploymentThreshold)
            }
            d if d == SetThresholdFailure::PermissionDeniedError as i32 => {
                Ok(SetThresholdFailure::PermissionDeniedError)
            }
            d if d == SetThresholdFailure::InsufficientTotalWeight as i32 => {
                Ok(SetThresholdFailure::InsufficientTotalWeight)
            }
            _ => Err(TryFromIntError(())),
        }
    }
}

/// Maximum number of associated keys (i.e. map of [`PublicKey`]s to [`Weight`]s) for a single
/// account.
pub const MAX_ASSOCIATED_KEYS: usize = 10;

/// The number of bytes in a serialized [`Weight`].
pub const WEIGHT_SERIALIZED_LENGTH: usize = U8_SERIALIZED_LENGTH;

/// The weight attributed to a given [`PublicKey`] in an account's associated keys.
#[derive(PartialOrd, Ord, PartialEq, Eq, Clone, Copy, Debug)]
pub struct Weight(u8);

impl Weight {
    /// Constructs a new `Weight`.
    pub fn new(weight: u8) -> Weight {
        Weight(weight)
    }

    /// Returns the value of `self` as a `u8`.
    pub fn value(self) -> u8 {
        self.0
    }
}

impl ToBytes for Weight {
    fn to_bytes(&self) -> Result<Vec<u8>, Error> {
        self.0.to_bytes()
    }

    fn serialized_length(&self) -> usize {
        WEIGHT_SERIALIZED_LENGTH
    }
}

impl FromBytes for Weight {
    fn from_bytes(bytes: &[u8]) -> Result<(Self, &[u8]), Error> {
        let (byte, rem) = u8::from_bytes(bytes)?;
        Ok((Weight::new(byte), rem))
    }
}

impl CLTyped for Weight {
    fn cl_type() -> CLType {
        CLType::U8
    }
}
/// The length in bytes of a [`PublicKey`].
pub const ED25519_LENGTH: usize = 32;

/// The number of bytes in a serialized [`Ed25519`].
pub const ED25519_SERIALIZED_LENGTH: usize = ED25519_LENGTH;

/// The upper bound of bytes in a serialized [`PublicKey`].
pub const PUBLIC_KEY_SERIALIZED_MAX_LENGTH: usize = ED25519_SERIALIZED_LENGTH;

/// A type alias for the raw bytes of an Ed25519 public key.
pub type Ed25519Bytes = [u8; ED25519_LENGTH];

/// A newtype wrapping a [`Ed25519Bytes`] which is the raw bytes of
/// the public key of an Ed25519 key pair.
#[derive(PartialOrd, Ord, PartialEq, Eq, Hash, Clone, Copy)]
pub struct Ed25519(Ed25519Bytes);

impl Ed25519 {
    /// Constructs a new `Ed25519` instance from the raw bytes of an Ed25519 public key.
    pub const fn new(value: Ed25519Bytes) -> Ed25519 {
        Ed25519(value)
    }

    /// Returns the raw bytes of the public key as an array.
    pub fn value(&self) -> Ed25519Bytes {
        self.0
    }

    /// Returns the raw bytes of the public key as a `slice`.
    pub fn as_bytes(&self) -> &[u8] {
        &self.0
    }
}

impl Display for Ed25519 {
    fn fmt(&self, f: &mut Formatter) -> core::fmt::Result {
        write!(f, "Ed25519({})", HexFmt(&self.0))
    }
}

impl ToBytes for Ed25519 {
    fn to_bytes(&self) -> Result<Vec<u8>, Error> {
        self.0.to_bytes()
    }

    fn serialized_length(&self) -> usize {
        ED25519_SERIALIZED_LENGTH
    }
}

impl FromBytes for Ed25519 {
    fn from_bytes(bytes: &[u8]) -> Result<(Self, &[u8]), Error> {
        let (bytes, rem) = <[u8; 32]>::from_bytes(bytes)?;
        Ok((Ed25519::new(bytes), rem))
    }
}

/// An enum of supported public key types.
#[derive(PartialOrd, Ord, PartialEq, Eq, Hash, Clone, Copy)]
pub enum PublicKey {
    /// An Ed25519 public key type.
    Ed25519(Ed25519),
}

impl Display for PublicKey {
    fn fmt(&self, f: &mut Formatter) -> core::fmt::Result {
        let PublicKey::Ed25519(ed25519) = self;
        write!(f, "PublicKey({})", ed25519)
    }
}

impl PublicKey {
    /// Constructs a new `PublicKey` using Ed25519 bytes.
    pub const fn ed25519_from(key: Ed25519Bytes) -> PublicKey {
        let ed25519 = Ed25519::new(key);
        PublicKey::Ed25519(ed25519)
    }

    /// Attemps a new `PublicKey` creation using a slice of bytes.
    pub fn ed25519_try_from(bytes: &[u8]) -> Result<PublicKey, TryFromSliceForPublicKeyError> {
        Ed25519Bytes::try_from(bytes)
            .map(PublicKey::ed25519_from)
            .map_err(|_| TryFromSliceForPublicKeyError(()))
    }

    /// Returns the raw bytes of the public key as an array.
    #[doc(hidden)]
    pub fn value(self) -> Ed25519Bytes {
        let PublicKey::Ed25519(ed25519) = self;
        ed25519.value()
    }

    /// Returns the raw bytes of the public key as a `slice`.
    pub fn as_bytes(&self) -> &[u8] {
        let PublicKey::Ed25519(ed25519) = self;
        ed25519.as_bytes()
    }
}

impl Debug for PublicKey {
    fn fmt(&self, f: &mut Formatter) -> core::fmt::Result {
        write!(f, "{}", self)
    }
}

impl CLTyped for PublicKey {
    fn cl_type() -> CLType {
        CLType::FixedList(Box::new(CLType::U8), 32)
    }
}

impl From<Ed25519> for PublicKey {
    fn from(ed25519: Ed25519) -> PublicKey {
        PublicKey::Ed25519(ed25519)
    }
}

impl ToBytes for PublicKey {
    fn to_bytes(&self) -> Result<Vec<u8>, Error> {
        let PublicKey::Ed25519(ed25519) = self;
        let mut bytes = Vec::with_capacity(PUBLIC_KEY_SERIALIZED_MAX_LENGTH);
        bytes.append(&mut ed25519.to_bytes()?);
        Ok(bytes)
    }

    fn serialized_length(&self) -> usize {
        PUBLIC_KEY_SERIALIZED_MAX_LENGTH
    }
}

impl FromBytes for PublicKey {
    fn from_bytes(bytes: &[u8]) -> Result<(Self, &[u8]), Error> {
        let (ed25519, rem) = Ed25519::from_bytes(bytes)?;
        Ok((PublicKey::from(ed25519), rem))
    }
}

/// Errors that can occur while adding a new [`PublicKey`] to an account's associated keys map.
#[derive(PartialEq, Eq, Fail, Debug, Copy, Clone)]
#[repr(i32)]
pub enum AddKeyFailure {
    /// There are already [`MAX_ASSOCIATED_KEYS`] [`PublicKey`]s associated with the given account.
    #[fail(display = "Unable to add new associated key because maximum amount of keys is reached")]
    MaxKeysLimit = 1,
    /// The given [`PublicKey`] is already associated with the given account.
    #[fail(display = "Unable to add new associated key because given key already exists")]
    DuplicateKey = 2,
    /// Caller doesn't have sufficient permissions to associate a new [`PublicKey`] with the given
    /// account.
    #[fail(display = "Unable to add new associated key due to insufficient permissions")]
    PermissionDenied = 3,
}

// This conversion is not intended to be used by third party crates.
#[doc(hidden)]
impl TryFrom<i32> for AddKeyFailure {
    type Error = TryFromIntError;

    fn try_from(value: i32) -> Result<Self, Self::Error> {
        match value {
            d if d == AddKeyFailure::MaxKeysLimit as i32 => Ok(AddKeyFailure::MaxKeysLimit),
            d if d == AddKeyFailure::DuplicateKey as i32 => Ok(AddKeyFailure::DuplicateKey),
            d if d == AddKeyFailure::PermissionDenied as i32 => Ok(AddKeyFailure::PermissionDenied),
            _ => Err(TryFromIntError(())),
        }
    }
}

/// Errors that can occur while removing a [`PublicKey`] from an account's associated keys map.
#[derive(Fail, Debug, Eq, PartialEq, Copy, Clone)]
#[repr(i32)]
pub enum RemoveKeyFailure {
    /// The given [`PublicKey`] is not associated with the given account.
    #[fail(display = "Unable to remove a key that does not exist")]
    MissingKey = 1,
    /// Caller doesn't have sufficient permissions to remove an associated [`PublicKey`] from the
    /// given account.
    #[fail(display = "Unable to remove associated key due to insufficient permissions")]
    PermissionDenied = 2,
    /// Removing the given associated [`PublicKey`] would cause the total weight of all remaining
    /// `PublicKey`s to fall below one of the action thresholds for the given account.
    #[fail(display = "Unable to remove a key which would violate action threshold constraints")]
    ThresholdViolation = 3,
}

// This conversion is not intended to be used by third party crates.
#[doc(hidden)]
impl TryFrom<i32> for RemoveKeyFailure {
    type Error = TryFromIntError;

    fn try_from(value: i32) -> Result<Self, Self::Error> {
        match value {
            d if d == RemoveKeyFailure::MissingKey as i32 => Ok(RemoveKeyFailure::MissingKey),
            d if d == RemoveKeyFailure::PermissionDenied as i32 => {
                Ok(RemoveKeyFailure::PermissionDenied)
            }
            d if d == RemoveKeyFailure::ThresholdViolation as i32 => {
                Ok(RemoveKeyFailure::ThresholdViolation)
            }
            _ => Err(TryFromIntError(())),
        }
    }
}

/// Errors that can occur while updating the [`Weight`] of a [`PublicKey`] in an account's
/// associated keys map.
#[derive(PartialEq, Eq, Fail, Debug, Copy, Clone)]
#[repr(i32)]
pub enum UpdateKeyFailure {
    /// The given [`PublicKey`] is not associated with the given account.
    #[fail(display = "Unable to update the value under an associated key that does not exist")]
    MissingKey = 1,
    /// Caller doesn't have sufficient permissions to update an associated [`PublicKey`] from the
    /// given account.
    #[fail(display = "Unable to update associated key due to insufficient permissions")]
    PermissionDenied = 2,
    /// Updating the [`Weight`] of the given associated [`PublicKey`] would cause the total weight
    /// of all `PublicKey`s to fall below one of the action thresholds for the given account.
    #[fail(display = "Unable to update weight that would fall below any of action thresholds")]
    ThresholdViolation = 3,
}

// This conversion is not intended to be used by third party crates.
#[doc(hidden)]
impl TryFrom<i32> for UpdateKeyFailure {
    type Error = TryFromIntError;

    fn try_from(value: i32) -> Result<Self, Self::Error> {
        match value {
            d if d == UpdateKeyFailure::MissingKey as i32 => Ok(UpdateKeyFailure::MissingKey),
            d if d == UpdateKeyFailure::PermissionDenied as i32 => {
                Ok(UpdateKeyFailure::PermissionDenied)
            }
            d if d == UpdateKeyFailure::ThresholdViolation as i32 => {
                Ok(UpdateKeyFailure::ThresholdViolation)
            }
            _ => Err(TryFromIntError(())),
        }
    }
}

#[cfg(test)]
mod tests {
    use std::{convert::TryFrom, vec::Vec};

    use super::*;

    #[test]
    fn ed25519_public_key_from_slice() {
        let bytes: Vec<u8> = (0..32).collect();
        let public_key = PublicKey::ed25519_try_from(&bytes[..]).expect("should create public key");
        assert_eq!(&bytes, &public_key.as_bytes());
    }
    #[test]
    fn ed25519_public_key_from_slice_too_small() {
        let _public_key =
            PublicKey::ed25519_try_from(&[0u8; 31][..]).expect_err("should not create public key");
    }

    #[test]
    fn ed25519_public_key_from_slice_too_big() {
        let _public_key =
            PublicKey::ed25519_try_from(&[0u8; 33][..]).expect_err("should not create public key");
    }

    #[test]
    fn try_from_i32_for_set_threshold_failure() {
        let max_valid_value_for_variant = SetThresholdFailure::InsufficientTotalWeight as i32;
        assert_eq!(
            Err(TryFromIntError(())),
            SetThresholdFailure::try_from(max_valid_value_for_variant + 1),
            "Did you forget to update `SetThresholdFailure::try_from` for a new variant of \
                   `SetThresholdFailure`, or `max_valid_value_for_variant` in this test?"
        );
    }

    #[test]
    fn try_from_i32_for_add_key_failure() {
        let max_valid_value_for_variant = AddKeyFailure::PermissionDenied as i32;
        assert_eq!(
            Err(TryFromIntError(())),
            AddKeyFailure::try_from(max_valid_value_for_variant + 1),
            "Did you forget to update `AddKeyFailure::try_from` for a new variant of \
                   `AddKeyFailure`, or `max_valid_value_for_variant` in this test?"
        );
    }

    #[test]
    fn try_from_i32_for_remove_key_failure() {
        let max_valid_value_for_variant = RemoveKeyFailure::ThresholdViolation as i32;
        assert_eq!(
            Err(TryFromIntError(())),
            RemoveKeyFailure::try_from(max_valid_value_for_variant + 1),
            "Did you forget to update `RemoveKeyFailure::try_from` for a new variant of \
                   `RemoveKeyFailure`, or `max_valid_value_for_variant` in this test?"
        );
    }

    #[test]
    fn try_from_i32_for_update_key_failure() {
        let max_valid_value_for_variant = UpdateKeyFailure::ThresholdViolation as i32;
        assert_eq!(
            Err(TryFromIntError(())),
            UpdateKeyFailure::try_from(max_valid_value_for_variant + 1),
            "Did you forget to update `UpdateKeyFailure::try_from` for a new variant of \
                   `UpdateKeyFailure`, or `max_valid_value_for_variant` in this test?"
        );
    }
}
