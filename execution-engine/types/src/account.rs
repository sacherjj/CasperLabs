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

pub const PURSE_ID_SERIALIZED_LENGTH: usize = UREF_SERIALIZED_LENGTH;

#[derive(Debug)]
pub struct TryFromIntError(());

#[derive(Debug)]
pub struct TryFromSliceForPublicKeyError(());

#[derive(Debug, Copy, Clone, Hash, PartialEq, Eq, PartialOrd, Ord)]
pub struct PurseId(URef);

impl PurseId {
    pub fn new(uref: URef) -> Self {
        PurseId(uref)
    }

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

#[repr(u32)]
pub enum ActionType {
    /// Required by deploy execution.
    Deployment = 0,
    /// Required when adding/removing associated keys, changing threshold
    /// levels.
    KeyManagement = 1,
}

/// convert from u32 representation of `[ActionType]`
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

/// Represents an error that occurs during the change of a thresholds on an
/// account.
///
/// It is represented by `i32` to be easily able to transform this value in an
/// out through FFI boundaries as a number.
///
/// The explicit numbering of the variants is done on purpose and whenever you
/// plan to add new variant, you should always extend it, and add a variant that
/// does not exist already. When adding new variants you should also remember to
/// change `From<i32> for SetThresholdFailure`.
///
/// This way we can ensure safety and backwards compatibility. Any changes
/// should be carefully reviewed and tested.
#[repr(i32)]
#[derive(Debug, Fail, PartialEq, Eq)]
pub enum SetThresholdFailure {
    #[fail(display = "New threshold should be lower or equal than deployment threshold")]
    KeyManagementThresholdError = 1,
    #[fail(display = "New threshold should be lower or equal than key management threshold")]
    DeploymentThresholdError = 2,
    #[fail(display = "Unable to set action threshold due to insufficient permissions")]
    PermissionDeniedError = 3,
    #[fail(
        display = "New threshold should be lower or equal than total weight of associated keys"
    )]
    InsufficientTotalWeight = 4,
}

/// convert from i32 representation of `[SetThresholdFailure]`
impl TryFrom<i32> for SetThresholdFailure {
    type Error = TryFromIntError;

    fn try_from(value: i32) -> Result<Self, Self::Error> {
        match value {
            d if d == SetThresholdFailure::KeyManagementThresholdError as i32 => {
                Ok(SetThresholdFailure::KeyManagementThresholdError)
            }
            d if d == SetThresholdFailure::DeploymentThresholdError as i32 => {
                Ok(SetThresholdFailure::DeploymentThresholdError)
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

pub const PUBLIC_KEY_LENGTH: usize = 32;

/// Maximum number of associated keys.
/// Value chosen arbitrary, shouldn't be too large to prevent bloating `associated_keys` table.
pub const MAX_KEYS: usize = 10;

pub const WEIGHT_SERIALIZED_LENGTH: usize = U8_SERIALIZED_LENGTH;

#[derive(PartialOrd, Ord, PartialEq, Eq, Clone, Copy, Debug)]
pub struct Weight(u8);

impl Weight {
    pub fn new(weight: u8) -> Weight {
        Weight(weight)
    }

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

#[derive(PartialOrd, Ord, PartialEq, Eq, Hash, Clone, Copy)]
pub struct PublicKey([u8; PUBLIC_KEY_LENGTH]);

impl Display for PublicKey {
    fn fmt(&self, f: &mut Formatter) -> core::fmt::Result {
        write!(f, "PublicKey({})", HexFmt(&self.0))
    }
}

impl Debug for PublicKey {
    fn fmt(&self, f: &mut Formatter) -> core::fmt::Result {
        write!(f, "{}", self)
    }
}

impl CLTyped for PublicKey {
    fn cl_type() -> CLType {
        CLType::FixedList(Box::new(CLType::U8), PUBLIC_KEY_LENGTH as u32)
    }
}

// TODO: This needs to be updated, `PUBLIC_KEY_SERIALIZED_LENGTH` is not 32 bytes as KEY_SIZE
// * U8_SIZE. I am not changing that as I don't want to deal with ripple effect.

// Public key is encoded as its underlying [u8; 32] array, which in turn
// is serialized as u8 + [u8; 32], u8 represents the length and then 32 element
// array.
pub const PUBLIC_KEY_SERIALIZED_LENGTH: usize = PUBLIC_KEY_LENGTH;

impl PublicKey {
    pub fn new(key: [u8; PUBLIC_KEY_LENGTH]) -> PublicKey {
        PublicKey(key)
    }

    pub fn value(self) -> [u8; PUBLIC_KEY_LENGTH] {
        self.0
    }

    /// Converts the underlying public key to a `Vec`
    pub fn to_vec(&self) -> Vec<u8> {
        self.0.to_vec()
    }
}

impl From<[u8; PUBLIC_KEY_LENGTH]> for PublicKey {
    fn from(key: [u8; PUBLIC_KEY_LENGTH]) -> Self {
        PublicKey(key)
    }
}

impl TryFrom<&[u8]> for PublicKey {
    type Error = TryFromSliceForPublicKeyError;
    fn try_from(bytes: &[u8]) -> Result<Self, Self::Error> {
        if bytes.len() != PUBLIC_KEY_LENGTH {
            return Err(TryFromSliceForPublicKeyError(()));
        }
        let mut public_key = [0u8; 32];
        public_key.copy_from_slice(bytes);
        Ok(PublicKey::new(public_key))
    }
}

impl ToBytes for PublicKey {
    fn to_bytes(&self) -> Result<Vec<u8>, Error> {
        ToBytes::to_bytes(&self.0)
    }
}

impl FromBytes for PublicKey {
    fn from_bytes(bytes: &[u8]) -> Result<(Self, &[u8]), Error> {
        let (key_bytes, rem): ([u8; PUBLIC_KEY_LENGTH], &[u8]) = FromBytes::from_bytes(bytes)?;
        Ok((PublicKey::new(key_bytes), rem))
    }
}

/// Represents an error that happens when trying to add a new associated key
/// on an account.
///
/// It is represented by `i32` to be easily able to transform this value in an
/// out through FFI boundaries as a number.
///
/// The explicit numbering of the variants is done on purpose and whenever you
/// plan to add new variant, you should always extend it, and add a variant that
/// does not exist already. When adding new variants you should also remember to
/// change `From<i32> for AddKeyFailure`.
///
/// This way we can ensure safety and backwards compatibility. Any changes
/// should be carefully reviewed and tested.
#[derive(PartialEq, Eq, Fail, Debug)]
#[repr(i32)]
pub enum AddKeyFailure {
    #[fail(display = "Unable to add new associated key because maximum amount of keys is reached")]
    MaxKeysLimit = 1,
    #[fail(display = "Unable to add new associated key because given key already exists")]
    DuplicateKey = 2,
    #[fail(display = "Unable to add new associated key due to insufficient permissions")]
    PermissionDenied = 3,
}

/// convert from i32 representation of `[AddKeyFailure]`
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

/// Represents an error that happens when trying to remove an associated key
/// from an account.
///
/// It is represented by `i32` to be easily able to transform this value in an
/// out through FFI boundaries as a number.
///
/// The explicit numbering of the variants is done on purpose and whenever you
/// plan to add new variant, you should always extend it, and add a variant that
/// does not exist already. When adding new variants you should also remember to
/// change `From<i32> for RemoveKeyFailure`.
///
/// This way we can ensure safety and backwards compatibility. Any changes
/// should be carefully reviewed and tested.
#[derive(Fail, Debug, Eq, PartialEq)]
#[repr(i32)]
pub enum RemoveKeyFailure {
    /// Key does not exist in the list of associated keys.
    #[fail(display = "Unable to remove a key that does not exist")]
    MissingKey = 1,
    #[fail(display = "Unable to remove associated key due to insufficient permissions")]
    PermissionDenied = 2,
    #[fail(display = "Unable to remove a key which would violate action threshold constraints")]
    ThresholdViolation = 3,
}

/// convert from i32 representation of `[RemoveKeyFailure]`
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

/// Represents an error that happens when trying to update the value under a
/// public key associated with an account.
///
/// It is represented by `i32` to be easily able to transform this value in and
/// out through FFI boundaries as a number.
///
/// For backwards compatibility, the variants are explicitly ordered and will
/// not be reordered; variants added in future versions will be appended to
/// extend the enum and in the event that a variant is removed its ordinal will
/// not be reused.
#[derive(PartialEq, Eq, Fail, Debug)]
#[repr(i32)]
pub enum UpdateKeyFailure {
    /// Key does not exist in the list of associated keys.
    #[fail(display = "Unable to update the value under an associated key that does not exist")]
    MissingKey = 1,
    #[fail(display = "Unable to add new associated key due to insufficient permissions")]
    PermissionDenied = 2,
    #[fail(display = "Unable to update weight that would fall below any of action thresholds")]
    ThresholdViolation = 3,
}

/// convert from i32 representation of `[UpdateKeyFailure]`
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

    use super::PublicKey;

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
}
