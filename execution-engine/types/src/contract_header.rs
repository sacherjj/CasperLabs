//! Data types for contract metadata (including version and method type signatures)

use crate::{
    bytesrepr::{self, FromBytes, ToBytes},
    uref::URef,
    CLType, CLTyped, ProtocolVersion, SemVer,
};
use alloc::{
    collections::{BTreeMap, BTreeSet},
    string::String,
    vec::Vec,
};
use core::convert::TryInto;
use num_derive::{FromPrimitive, ToPrimitive};
use num_traits::{FromPrimitive, ToPrimitive};

/// Set of errors which may happen when working with contract headers.
#[derive(Debug, PartialEq, FromPrimitive, ToPrimitive)]
#[repr(u8)]
pub enum Error {
    /// Attempt to add/remove contract versions without the right access key.
    InvalidAccessKey = 1,
    /// Attempt to override an existing or previously existing version with a
    /// new header (this is not allowed to ensure immutability of a given
    /// version).
    PreviouslyUsedVersion = 2,
    /// Attempted to remove a version that does not exist.
    VersionNotFound = 3,
}

impl Error {
    /// Convert to byte for serialization purposes.
    pub fn to_u8(self) -> u8 {
        ToPrimitive::to_u8(&self).unwrap()
    }

    /// Construct from byte (for serialization purposes).
    pub fn from_i32(x: i32) -> Option<Self> {
        let y: u8 = x.try_into().ok()?;
        FromPrimitive::from_u8(y)
    }
}

/// Collection of different versions of the same contract.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ContractMetadata {
    /// Key used to add or remove versions
    access_key: URef,
    /// Versions that can be called
    active_versions: BTreeMap<SemVer, ContractHeader>,
    /// Old versions that are no longer supported
    removed_versions: BTreeSet<SemVer>,
}

impl ContractMetadata {
    /// Create new `ContractMetadata` (with no versions) from given access key.
    pub fn new(access_key: URef) -> Self {
        ContractMetadata {
            access_key,
            active_versions: BTreeMap::new(),
            removed_versions: BTreeSet::new(),
        }
    }

    /// Get the access key for this ContractMetadata.
    pub fn access_key(&self) -> URef {
        self.access_key
    }

    /// Modify the collection of active versions to include the given one.
    pub fn with_version(&mut self, version: SemVer, header: ContractHeader) -> Result<(), Error> {
        if self.removed_versions.contains(&version) || self.active_versions.contains_key(&version) {
            return Err(Error::PreviouslyUsedVersion);
        }

        self.active_versions.insert(version, header);
        Ok(())
    }

    /// Remove the given version from active versions, putting it into removed versions.
    pub fn remove_version(&mut self, version: SemVer) -> Result<(), Error> {
        if self.removed_versions.contains(&version) {
            return Ok(());
        } else if !self.active_versions.contains_key(&version) {
            return Err(Error::VersionNotFound);
        }

        self.active_versions.remove(&version);
        self.removed_versions.insert(version);
        Ok(())
    }
}

impl CLTyped for ContractMetadata {
    fn cl_type() -> CLType {
        CLType::ContractMetadata
    }
}

impl ToBytes for ContractMetadata {
    fn to_bytes(&self) -> Result<Vec<u8>, bytesrepr::Error> {
        Ok(Vec::new()) // TODO: real serialization
    }

    fn serialized_length(&self) -> usize {
        0
    }
}

impl FromBytes for ContractMetadata {
    fn from_bytes(bytes: &[u8]) -> Result<(Self, &[u8]), bytesrepr::Error> {
        Err(bytesrepr::Error::Formatting) // TODO: real serialization
    }
}

/// Methods and type signatures supported by a contract.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ContractHeader {
    methods: BTreeMap<String, EntryPoint>,
    protocol_version: ProtocolVersion,
}

impl ContractHeader {
    /// Checks whether there is a method with the given name
    pub fn has_method_name(&self, name: &str) -> bool {
        self.methods.contains_key(name)
    }

    /// Returns the list of method names
    pub fn method_names(&self) -> Vec<&str> {
        self.methods.keys().map(|s| s.as_str()).collect()
    }

    /// Get the protocol version this header is targeting.
    pub fn protocol_version(&self) -> ProtocolVersion {
        self.protocol_version
    }
}

impl ToBytes for ContractHeader {
    fn to_bytes(&self) -> Result<Vec<u8>, bytesrepr::Error> {
        Ok(Vec::new()) // TODO: real serialization
    }

    fn serialized_length(&self) -> usize {
        0
    }
}

impl FromBytes for ContractHeader {
    fn from_bytes(bytes: &[u8]) -> Result<(Self, &[u8]), bytesrepr::Error> {
        Err(bytesrepr::Error::Formatting) // TODO: real serialization
    }
}

/// Type signature of a method. Order of arguments matter since can be
/// referenced by index as well as name.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct EntryPoint {
    // TODO: I wonder if we could have access-controlled methods
    args: Vec<Arg>,
    ret: CLType,
}

/// Argument to a method
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Arg {
    name: String,
    cl_type: CLType,
}
