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
    CLType, CLTyped, URef, UREF_SERIALIZED_LENGTH,
};

/// The number of bytes in a serialized [`PurseId`].
pub const PURSE_ID_SERIALIZED_LENGTH: usize = UREF_SERIALIZED_LENGTH;

// This error type is not intended to be used by third party crates.
#[doc(hidden)]
#[derive(Debug, Eq, PartialEq)]
pub struct TryFromIntError(());

/// Associated error type of `TryFrom<&[u8]>` for [`PublicKey`].
#[derive(Debug)]
pub struct TryFromSliceForPublicKeyError(());

/// A newtype wrapping a [`URef`](crate::URef) which represents the ID of a purse.
#[derive(Debug, Copy, Clone, Hash, PartialEq, Eq, PartialOrd, Ord)]
pub struct PurseId(URef);

impl PurseId {
    /// Constructs a `PurseId` from a `URef`.
    pub fn new(uref: URef) -> Self {
        PurseId(uref)
    }

    /// Returns the wrapped `URef`.
    pub fn value(&self) -> URef {
        self.0
    }
}

impl From<PurseId> for URef {
    fn from(purse_id: PurseId) -> URef {
        purse_id.value()
    }
}

impl ToBytes for PurseId {
    fn to_bytes(&self) -> Result<Vec<u8>, Error> {
        ToBytes::to_bytes(&self.0)
    }
}

impl FromBytes for PurseId {
    fn from_bytes(bytes: &[u8]) -> Result<(Self, &[u8]), Error> {
        <URef>::from_bytes(bytes).map(|(uref, rem)| (PurseId::new(uref), rem))
    }
}

impl CLTyped for PurseId {
    fn cl_type() -> CLType {
        CLType::URef
    }
}

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
#[derive(Debug, Fail, PartialEq, Eq)]
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
        ToBytes::to_bytes(&self.0)
    }
}

impl FromBytes for Weight {
    fn from_bytes(bytes: &[u8]) -> Result<(Self, &[u8]), Error> {
        let (byte, rem): (u8, &[u8]) = FromBytes::from_bytes(bytes)?;
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

/// Identifies a serialized public key of Ed25519 variant.
const PUBLIC_KEY_ED25519_ID: u8 = 0;

/// The number of bytes that a variant tag occupies in serialized [`PublicKey`].
const PUBLIC_KEY_ID_SERIALIZED_LENGTH: usize = 1;

/// The number of bytes in a serialized [`PublicKey`].
pub const PUBLIC_KEY_SERIALIZED_LENGTH: usize = PUBLIC_KEY_ID_SERIALIZED_LENGTH + ED25519_LENGTH;

/// A newtype wrapping a [`[u8; ED25519_LENGTH]`](ED25519_LENGTH) which is the raw bytes of
/// the public key of a cryptographic asymmetric key pair.
#[derive(PartialOrd, Ord, PartialEq, Eq, Hash, Clone, Copy)]
pub struct Ed25519([u8; ED25519_LENGTH]);

impl Ed25519 {
    /// Constructs new [`Ed25519`] instance from raw bytes representing a Ed25519 public key
    pub const fn new(value: [u8; ED25519_LENGTH]) -> Ed25519 {
        Ed25519(value)
    }

    /// Returns the raw bytes of the public key as an array.
    pub fn value(&self) -> [u8; ED25519_LENGTH] {
        self.0
    }
}


impl Display for Ed25519 {
    fn fmt(&self, f: &mut Formatter) -> core::fmt::Result {
        write!(f, "Ed25519({})", HexFmt(&self.0))
    }
}

impl ToBytes for Ed25519 {
    fn to_bytes(&self) -> Result<Vec<u8>, Error> {
        ToBytes::to_bytes(&self.0)
    }
}

impl FromBytes for Ed25519 {
    fn from_bytes(bytes: &[u8]) -> Result<(Self, &[u8]), Error> {
        let (bytes, rem): ([u8; 32], &[u8]) = FromBytes::from_bytes(bytes)?;
        Ok((Ed25519::new(bytes), rem))
    }
}

/// An enum wrapping various variants of supported public key types.
#[derive(PartialOrd, Ord, PartialEq, Eq, Hash, Clone, Copy)]
pub enum PublicKey {
    /// Represents an Ed25519 public key type
    Ed25519(Ed25519),
}

impl Display for PublicKey {
    fn fmt(&self, f: &mut Formatter) -> core::fmt::Result {
        let PublicKey::Ed25519(ed25519) = self;
        write!(f, "PublicKey({})", ed25519)
    }
}

impl PublicKey {
    /// Constructs a new `PublicKey`.
    pub const fn new(key: [u8; ED25519_LENGTH]) -> PublicKey {
        let ed25519 = Ed25519::new(key);
        PublicKey::from_ed25519(ed25519)
    }

    /// Constructs a new [`PublicKey`] from an existing instance of
    /// [`Ed25519`] public key
    pub const fn from_ed25519(ed25519: Ed25519) -> PublicKey {
        PublicKey::Ed25519(ed25519)
    }

    /// Returns the raw bytes of the public key as an array.
    pub fn value(self) -> [u8; ED25519_LENGTH] {
        let PublicKey::Ed25519(ed25519) = self;
        ed25519.value()
    }

    /// Returns the raw bytes of the public key as a `Vec`.
    pub fn to_vec(&self) -> Vec<u8> {
        let PublicKey::Ed25519(ed25519) = self;
        let bytes = ed25519.value();
        bytes.to_vec()
    }
}

impl Debug for PublicKey {
    fn fmt(&self, f: &mut Formatter) -> core::fmt::Result {
        write!(f, "{}", self)
    }
}

impl CLTyped for PublicKey {
    fn cl_type() -> CLType {
        CLType::FixedList(Box::new(CLType::U8), ED25519_LENGTH as u32)
    }
}

impl From<[u8; ED25519_LENGTH]> for PublicKey {
    fn from(key: [u8; ED25519_LENGTH]) -> Self {
        PublicKey::Ed25519(Ed25519::new(key))
    }
}

impl From<Ed25519> for PublicKey {
    fn from(ed25519: Ed25519) -> PublicKey {
        PublicKey::from_ed25519(ed25519)
    }
}

impl TryFrom<&[u8]> for PublicKey {
    type Error = TryFromSliceForPublicKeyError;
    fn try_from(bytes: &[u8]) -> Result<Self, Self::Error> {
        if bytes.len() != ED25519_LENGTH {
            return Err(TryFromSliceForPublicKeyError(()));
        }
        let mut public_key = [0u8; 32];
        public_key.copy_from_slice(bytes);
        Ok(PublicKey::new(public_key))
    }
}

impl ToBytes for PublicKey {
    fn to_bytes(&self) -> Result<Vec<u8>, Error> {
        let PublicKey::Ed25519(ed25519) = self;
        let mut bytes = ed25519.to_bytes()?;
        bytes.insert(0, PUBLIC_KEY_ED25519_ID);
        Ok(bytes)
    }
}

impl FromBytes for PublicKey {
    fn from_bytes(bytes: &[u8]) -> Result<(Self, &[u8]), Error> {
        let (variant_tag, rem1) = u8::from_bytes(bytes)?;
        match variant_tag {
            PUBLIC_KEY_ED25519_ID => {
                let (ed25519, rem2) = Ed25519::from_bytes(rem1)?;
                Ok((PublicKey::from(ed25519), rem2))
            },
            _ => Err(Error::Formatting),
        }
    }
}

/// Errors that can occur while adding a new [`PublicKey`] to an account's associated keys map.
#[derive(PartialEq, Eq, Fail, Debug)]
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
#[derive(Fail, Debug, Eq, PartialEq)]
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
#[derive(PartialEq, Eq, Fail, Debug)]
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
    fn public_key_from_slice() {
        let bytes: Vec<u8> = (0..32).collect();
        let public_key = PublicKey::try_from(&bytes[..]).expect("should create public key");
        assert_eq!(&bytes, &public_key.value());
    }
    #[test]
    fn public_key_from_slice_too_small() {
        let _public_key =
            PublicKey::try_from(&[0u8; 31][..]).expect_err("should not create public key");
    }

    #[test]
    fn public_key_from_slice_too_big() {
        let _public_key =
            PublicKey::try_from(&[0u8; 33][..]).expect_err("should not create public key");
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
