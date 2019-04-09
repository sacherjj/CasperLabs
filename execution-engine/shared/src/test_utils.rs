//! Some functions to use in tests.

use common::bytesrepr::{deserialize, FromBytes, ToBytes};

/// Returns `true` if a we can serialize and then deserialize a value
pub fn test_serialization_roundtrip<T>(t: &T) -> bool
where
    T: ToBytes + FromBytes + PartialEq + std::fmt::Debug,
{
    match deserialize::<T>(&ToBytes::to_bytes(t).expect("Unable to serialize data"))
        .map(|r| r == *t)
        .ok()
    {
        Some(true) => true,
        Some(false) => false,
        None => false,
    }
}
