use crate::{uref::URef, CLType, ProtocolVersion, SemVer};
use alloc::{
    collections::{BTreeMap, BTreeSet},
    string::String,
    vec::Vec,
};

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

/// Methods and type signatures supported by a contract.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ContractHeader {
    methods: BTreeMap<String, EntryPoint>,
    protocol_version: ProtocolVersion,
}

/// Type signature of a method. Order of arguments matter since can be
/// referenced by index as well as name.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct EntryPoint {
    args: Vec<Arg>,
    ret: CLType,
}

/// Argument to a method
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Arg {
    name: String,
    cl_type: CLType,
}
