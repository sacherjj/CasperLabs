//! Some newtypes.
use core::array::TryFromSliceError;
use std::convert::TryFrom;
use std::ops::Deref;

use blake2::digest::{Input, VariableOutput};
use blake2::VarBlake2b;

use common::bytesrepr::{self, FromBytes, ToBytes};
use serde::Serialize;
use std::fmt;
use uuid::Uuid;

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

impl Into<[u8; BLAKE2B_DIGEST_LENGTH]> for Blake2bHash {
    fn into(self) -> [u8; BLAKE2B_DIGEST_LENGTH] {
        self.0
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

#[derive(Clone, Copy, Debug, Hash, PartialEq, Serialize)]
pub struct CorrelationId(Uuid);

impl CorrelationId {
    pub fn new() -> CorrelationId {
        CorrelationId(Uuid::new_v4())
    }

    pub fn is_empty(&self) -> bool {
        self.0.is_nil()
    }
}

impl fmt::Display for CorrelationId {
    fn fmt(&self, f: &mut fmt::Formatter) -> fmt::Result {
        write!(f, "{:?}", self)
    }
}

#[cfg(test)]
mod tests {
    use crate::logging;
    use crate::newtypes::CorrelationId;
    use std::hash::{Hash, Hasher};

    #[test]
    fn should_be_able_to_generate_correlation_id() {
        let correlation_id = CorrelationId::new();

        //println!("{}", correlation_id.to_string());

        assert_ne!(
            correlation_id.to_string(),
            "00000000-0000-0000-0000-000000000000",
            "should not be empty value"
        )
    }

    #[test]
    fn should_support_to_string() {
        let correlation_id = CorrelationId::new();

        let correlation_id_string = correlation_id.to_string();

        assert!(
            !correlation_id_string.is_empty(),
            "correlation_id should be produce string"
        )
    }

    #[test]
    fn should_support_to_json() {
        let correlation_id = CorrelationId::new();

        let correlation_id_json = logging::utils::jsonify(correlation_id, false);

        assert!(
            !correlation_id_json.is_empty(),
            "correlation_id should be produce json"
        )
    }

    #[test]
    fn should_support_is_display() {
        let correlation_id = CorrelationId::new();

        let display = format!("{}", correlation_id);

        assert!(!display.is_empty(), "display should not be empty")
    }

    #[test]
    fn should_support_is_empty() {
        let correlation_id = CorrelationId::new();

        assert!(
            !correlation_id.is_empty(),
            "correlation_id should not be empty"
        )
    }

    #[test]
    fn should_create_unique_id_on_new() {
        let correlation_id_lhs = CorrelationId::new();
        let correlation_id_rhs = CorrelationId::new();

        assert_ne!(
            correlation_id_lhs, correlation_id_rhs,
            "correlation_ids should be distinct"
        );
    }

    #[test]
    fn should_support_clone() {
        let correlation_id = CorrelationId::new();

        let cloned = correlation_id.clone();

        assert_eq!(correlation_id, cloned, "should be cloneable")
    }

    #[test]
    fn should_support_copy() {
        let correlation_id = CorrelationId::new();

        let cloned = correlation_id;

        assert_eq!(correlation_id, cloned, "should be cloneable")
    }

    #[test]
    fn should_support_hash() {
        let correlation_id = CorrelationId::new();

        let mut state = std::collections::hash_map::DefaultHasher::new();

        correlation_id.hash(&mut state);

        let hash = state.finish();

        assert!(hash > 0, "should be hashable");
    }
}
