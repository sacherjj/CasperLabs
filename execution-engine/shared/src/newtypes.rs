//! Some newtypes.

use core::array::TryFromSliceError;
use std::convert::TryFrom;
use std::ops::Deref;

use blake2::digest::{Input, VariableOutput};
use blake2::VarBlake2b;

use common::bytesrepr::{self, FromBytes, ToBytes};

const BLAKE2B_DIGEST_LENGTH: usize = 32;

/// Represents a 32-byte BLAKE2b hash digest
#[derive(Copy, Clone, Debug, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub struct Blake2bHash([u8; BLAKE2B_DIGEST_LENGTH]);

impl Blake2bHash {
    /// Creates a 32-byte BLAKE2b hash digest from a given a piece of data
    pub fn new(data: &[u8]) -> Self {
        let mut ret = [0u8; BLAKE2B_DIGEST_LENGTH];
        // Safe to unwrap here because our digest length is constant and valid
        let mut hasher = VarBlake2b::new(BLAKE2B_DIGEST_LENGTH).unwrap();
        hasher.input(data);
        hasher.variable_result(|hash| ret.clone_from_slice(hash));
        Blake2bHash(ret)
    }

    /// Converts the underlying BLAKE2b hash digest array to a `Vec`
    pub fn to_vec(&self) -> Vec<u8> {
        self.0.to_vec()
    }
}

impl From<[u8; BLAKE2B_DIGEST_LENGTH]> for Blake2bHash {
    fn from(arr: [u8; BLAKE2B_DIGEST_LENGTH]) -> Self {
        Blake2bHash(arr)
    }
}

impl<'a> TryFrom<&'a [u8]> for Blake2bHash {
    type Error = TryFromSliceError;

    fn try_from(slice: &[u8]) -> Result<Blake2bHash, Self::Error> {
        <[u8; BLAKE2B_DIGEST_LENGTH]>::try_from(slice).map(Blake2bHash)
    }
}

impl ToBytes for Blake2bHash {
    fn to_bytes(&self) -> Result<Vec<u8>, bytesrepr::Error> {
        ToBytes::to_bytes(&self.0)
    }
}

impl FromBytes for Blake2bHash {
    fn from_bytes(bytes: &[u8]) -> Result<(Self, &[u8]), bytesrepr::Error> {
        FromBytes::from_bytes(bytes).map(|(arr, rem)| (Blake2bHash(arr), rem))
    }
}

/// Represents a validated value.  Validation is user-specified.
pub struct Validated<T>(T);

impl<T> Validated<T> {
    /// Creates a validated value from a given value and validation function.
    pub fn new<E, F>(v: T, guard: F) -> Result<Validated<T>, E>
    where
        F: Fn(&T) -> Result<(), E>,
    {
        guard(&v).map(|_| Validated(v))
    }

    /// A validation function which always succeeds.
    pub fn valid(_v: &T) -> Result<(), !> {
        Ok(())
    }

    pub fn into_raw(self) -> T {
        self.0
    }
}

impl<T: Clone> Clone for Validated<T> {
    fn clone(&self) -> Self {
        Validated(self.0.clone())
    }
}

impl<T: Clone> Deref for Validated<T> {
    type Target = T;

    fn deref(&self) -> &T {
        &self.0
    }
}
