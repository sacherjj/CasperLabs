//! Data types for contract metadata (including version and method type signatures)

use crate::{
    bytesrepr::{self, FromBytes, ToBytes, U32_SERIALIZED_LENGTH, U8_SERIALIZED_LENGTH},
    uref::URef,
    AccessRights, CLType, ContractHash, ContractPackageHash, ContractWasmHash, Key,
    ProtocolVersion, KEY_HASH_LENGTH,
};
use alloc::{
    collections::{BTreeMap, BTreeSet},
    string::String,
    vec::Vec,
};
use core::fmt;

/// Maximum number of distinct user groups.
pub const MAX_GROUP_UREFS: u8 = 10;
/// Maximum number of URefs which can be assigned across all user groups.
pub const MAX_TOTAL_UREFS: usize = 100;

/// Set of errors which may happen when working with contract headers.
#[derive(Debug, PartialEq)]
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
    /// Attempted to create a user group which already exists (use the update
    /// function to change an existing user group).
    GroupAlreadyExists = 4,
    /// Attempted to add a new user group which exceeds the allowed maximum
    /// number of groups.
    MaxGroupsExceeded = 5,
    /// Attempted to add a new URef to a group, which resulted in the total
    /// number of URefs across all user groups to exceed the allowed maximum.
    MaxTotalURefsExceeded = 6,
    /// Attempted to remove a URef from a group, which does not exist in the
    /// group.
    GroupDoesNotExist = 7,
    /// Attempted to remove unknown URef from the group.
    UnableToRemoveURef = 8,
    /// Group is use by at least one active contract.
    GroupInUse = 9,
}

/// A (labelled) "user group". Each method of a versioned contract may be
/// assoicated with one or more user groups which are allowed to call it.
#[derive(Debug, Clone, PartialEq, Eq, PartialOrd, Ord)]
pub struct Group(String);

impl Group {
    /// Basic constructor
    pub fn new<T: Into<String>>(s: T) -> Self {
        Group(s.into())
    }

    /// Retrieves underlying name.
    pub fn value(&self) -> &str {
        &self.0
    }
}

impl Into<String> for Group {
    fn into(self) -> String {
        self.0
    }
}

impl ToBytes for Group {
    fn to_bytes(&self) -> Result<Vec<u8>, bytesrepr::Error> {
        self.0.to_bytes()
    }

    fn serialized_length(&self) -> usize {
        self.0.serialized_length()
    }
}

impl FromBytes for Group {
    fn from_bytes(bytes: &[u8]) -> Result<(Self, &[u8]), bytesrepr::Error> {
        String::from_bytes(bytes).map(|(label, bytes)| (Group(label), bytes))
    }
}

/// Automatically incremented value for a contract version within a major `ProtocolVersion`.
pub type ContractVersion = u8;

/// Within each discrete major `ProtocolVersion`, contract version resets to this value.
pub const CONTRACT_INITIAL_VERSION: ContractVersion = 1;

/// Major element of `ProtocolVersion` a `ContractVersion` is compatible with.
pub type ProtocolVersionMajor = u32;

/// Major element of `ProtocolVersion` combined with `ContractVersion`.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub struct ContractVersionKey(ProtocolVersionMajor, ContractVersion);

impl ContractVersionKey {
    /// Returns a new instance of ContractVersionKey with provided values.
    pub fn new(
        protocol_version_major: ProtocolVersionMajor,
        contract_version: ContractVersion,
    ) -> Self {
        Self(protocol_version_major, contract_version)
    }

    /// Returns the major element of the protocol version this contract is compatible with.
    pub fn protocol_version_major(&self) -> ProtocolVersionMajor {
        self.0
    }

    /// Returns the contract version within the protocol major version.
    pub fn contract_version(&self) -> ContractVersion {
        self.1
    }
}

impl Into<(ProtocolVersionMajor, ContractVersion)> for ContractVersionKey {
    fn into(self) -> (ProtocolVersionMajor, ContractVersion) {
        (self.0, self.1)
    }
}

/// Serialized length of `ContractVersionKey`.
pub const CONTRACT_VERSION_KEY_SERIALIZED_LENGTH: usize =
    U32_SERIALIZED_LENGTH + U8_SERIALIZED_LENGTH;

impl ToBytes for ContractVersionKey {
    fn to_bytes(&self) -> Result<Vec<u8>, bytesrepr::Error> {
        let mut ret = bytesrepr::unchecked_allocate_buffer(self);
        ret.append(&mut self.0.to_bytes()?);
        ret.append(&mut self.1.to_bytes()?);
        Ok(ret)
    }

    fn serialized_length(&self) -> usize {
        CONTRACT_VERSION_KEY_SERIALIZED_LENGTH
    }
}

impl FromBytes for ContractVersionKey {
    fn from_bytes(bytes: &[u8]) -> Result<(Self, &[u8]), bytesrepr::Error> {
        let (major, rem): (u32, &[u8]) = FromBytes::from_bytes(bytes)?;
        let (contract, rem): (u8, &[u8]) = FromBytes::from_bytes(rem)?;
        Ok((ContractVersionKey::new(major, contract), rem))
    }
}

impl fmt::Display for ContractVersionKey {
    fn fmt(&self, f: &mut fmt::Formatter) -> fmt::Result {
        write!(f, "{}.{}", self.0, self.1)
    }
}

/// Collection of stored contracts.
pub type ContractVersions = BTreeMap<ContractVersionKey, ContractHash>;

/// Collection of contract versions that are no longer supported.
pub type RemovedVersions = BTreeSet<ContractVersionKey>;

/// Collection of different versions of the same contract.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ContractPackage {
    /// Key used to add or remove versions
    access_key: URef,
    versions: ContractVersions,
    /// Versions that can be called
    // versions: BTreeMap<SemVer, Contract>,
    /// Old versions that are no longer supported
    disabled_versions: RemovedVersions,
    /// Mapping maintaining the set of URefs associated with each "user
    /// group". This can be used to control access to methods in a particular
    /// version of the contract. A method is callable by any context which
    /// "knows" any of the URefs assoicated with the mthod's user group.
    groups: BTreeMap<Group, BTreeSet<URef>>,
}

impl Default for ContractPackage {
    fn default() -> Self {
        // Default impl used for temporary backwards compatibility
        let mut versions = ContractVersions::new();

        let key = ContractVersionKey::new(
            ProtocolVersion::V1_0_0.value().major,
            CONTRACT_INITIAL_VERSION,
        );
        versions.insert(key, [0; 32]);
        ContractPackage {
            access_key: URef::new([0; 32], AccessRights::NONE),
            versions,
            disabled_versions: BTreeSet::new(),
            groups: BTreeMap::new(),
        }
    }
}

impl ContractPackage {
    /// Create new `ContractMetadata` (with no versions) from given access key.
    pub fn new(access_key: URef) -> Self {
        ContractPackage {
            access_key,
            versions: BTreeMap::new(),
            disabled_versions: BTreeSet::new(),
            groups: BTreeMap::new(),
        }
    }

    /// Get the access key for this ContractMetadata.
    pub fn access_key(&self) -> URef {
        self.access_key
    }

    /// Get the mutable group definitions for this contract.
    pub fn groups_mut(&mut self) -> &mut BTreeMap<Group, BTreeSet<URef>> {
        &mut self.groups
    }

    /// Get the group definitions for this contract.
    pub fn groups(&self) -> &BTreeMap<Group, BTreeSet<URef>> {
        &self.groups
    }

    /// Adds new group to the user groups set
    pub fn add_group(&mut self, group: Group, urefs: BTreeSet<URef>) {
        let v = self.groups.entry(group).or_insert_with(Default::default);
        v.extend(urefs)
    }

    /// Get the contract header for the given version (if present)
    pub fn get_contract(&self, contract_version_key: &ContractVersionKey) -> Option<&ContractHash> {
        self.versions.get(contract_version_key)
    }

    /// Checks if the given version is active
    pub fn is_version_active(&self, contract_version_key: &ContractVersionKey) -> bool {
        self.versions.contains_key(contract_version_key)
    }

    /// Modify the collection of active versions to include the given one.
    pub fn insert_contract_version(
        &mut self,
        protocol_version_major: ProtocolVersionMajor,
        contract_hash: ContractHash,
    ) -> ContractVersionKey {
        let contract_version = self.next_contract_version_for(protocol_version_major);
        let key = ContractVersionKey::new(protocol_version_major, contract_version);
        self.versions.insert(key.clone(), contract_hash);
        key
    }

    /// Checks if the given version exists and is available for use
    pub fn is_contract_version_in_use(&self, contract_version_key: &ContractVersionKey) -> bool {
        self.versions.contains_key(contract_version_key)
            && !self.disabled_versions.contains(contract_version_key)
    }

    /// Remove the given version from active versions, putting it into removed versions.
    pub fn disable_contract_version(
        &mut self,
        contract_version_key: &ContractVersionKey,
    ) -> Result<(), Error> {
        if !self.versions.contains_key(contract_version_key) {
            return Err(Error::VersionNotFound);
        }
        // idempotent; do not error if already removed
        self.disabled_versions.insert(contract_version_key.clone());
        Ok(())
    }

    /// Returns mutable reference to active versions.
    pub fn versions_mut(&mut self) -> &mut ContractVersions {
        &mut self.versions
    }

    /// Get removed versions set.
    pub fn removed_versions(&self) -> &RemovedVersions {
        &self.disabled_versions
    }

    /// Returns mutable versions
    pub fn removed_versions_mut(&mut self) -> &mut RemovedVersions {
        &mut self.disabled_versions
    }

    // /// Checks if a given group is in use in at least one active contracts.
    // fn is_user_group_in_use(&self, group: &Group) -> bool {
    //     for versions in self.versions.values() {
    //         for entrypoint in versions.entry_points().values() {
    //             if let EntryPointAccess::Groups(groups) = entrypoint.access() {
    //                 if groups.contains(group) {
    //                     return true;
    //                 }
    //             }
    //         }
    //     }
    //     false
    // }

    /// Removes a user group.
    ///
    /// Returns true if group could be removed, and false otherwise if the user group is still
    /// active.
    pub fn remove_group(&mut self, group: &Group) -> bool {
        // if self.is_user_group_in_use(group) {
        //     return false;
        // }

        self.groups.remove(group).is_some()
    }

    /// Gets next contract version for given protocol version
    pub fn next_contract_version_for(
        &self,
        protocol_version: ProtocolVersionMajor,
    ) -> ContractVersion {
        self.versions
            .keys()
            .filter_map(|&contract_version_key| {
                if contract_version_key.protocol_version_major() == protocol_version {
                    Some(contract_version_key.contract_version())
                } else {
                    None
                }
            })
            .last()
            .or(Some(CONTRACT_INITIAL_VERSION))
            .unwrap()
    }
}

impl ToBytes for ContractPackage {
    fn to_bytes(&self) -> Result<Vec<u8>, bytesrepr::Error> {
        let mut result = bytesrepr::allocate_buffer(self)?;

        result.append(&mut self.access_key.to_bytes()?);
        result.append(&mut self.versions.to_bytes()?);
        result.append(&mut self.disabled_versions.to_bytes()?);
        result.append(&mut self.groups.to_bytes()?);

        Ok(result)
    }

    fn serialized_length(&self) -> usize {
        self.access_key.serialized_length()
            + self.versions.serialized_length()
            + self.disabled_versions.serialized_length()
            + self.groups.serialized_length()
    }
}

impl FromBytes for ContractPackage {
    fn from_bytes(bytes: &[u8]) -> Result<(Self, &[u8]), bytesrepr::Error> {
        let (access_key, bytes) = URef::from_bytes(bytes)?;
        let (versions, bytes) = ContractVersions::from_bytes(bytes)?;
        let (removed_versions, bytes) = RemovedVersions::from_bytes(bytes)?;
        let (groups, bytes) = BTreeMap::<Group, BTreeSet<URef>>::from_bytes(bytes)?;
        let result = ContractPackage {
            access_key,
            versions,
            disabled_versions: removed_versions,
            groups,
        };

        Ok((result, bytes))
    }
}
/// Collection of named entry points
pub type EntryPoints = BTreeMap<String, EntryPoint>;

/// Collection of named keys
pub type NamedKeys = BTreeMap<String, Key>;

/// Methods and type signatures supported by a contract.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Contract {
    contract_package_hash: ContractPackageHash,
    contract_wasm_hash: ContractWasmHash,
    named_keys: NamedKeys,
    entry_points: EntryPoints,
    protocol_version: ProtocolVersion,
}

impl Contract {
    /// `Contract` constructor.
    pub fn new(
        contract_package_hash: ContractPackageHash,
        contract_wasm_hash: ContractWasmHash,
        named_keys: NamedKeys,
        entry_points: EntryPoints,
        protocol_version: ProtocolVersion,
    ) -> Self {
        Contract {
            contract_package_hash,
            contract_wasm_hash,
            named_keys,
            entry_points,
            protocol_version,
        }
    }

    /// Hash for accessing contract package
    pub fn contract_package_hash(&self) -> ContractPackageHash {
        self.contract_package_hash
    }

    /// Checks whether there is a method with the given name
    pub fn has_entry_point(&self, name: &str) -> bool {
        self.entry_points.contains_key(name)
    }

    /// Returns the type signature for the given `method`.
    pub fn get_entry_point(&self, method: &str) -> Option<&EntryPoint> {
        self.entry_points.get(method)
    }

    /// Get the protocol version this header is targeting.
    pub fn protocol_version(&self) -> ProtocolVersion {
        self.protocol_version
    }

    /// Adds new entry point
    pub fn add_entry_point<T: Into<String>>(&mut self, name: T, entrypoint: EntryPoint) {
        self.entry_points.insert(name.into(), entrypoint);
    }

    /// Hash for accessing contract bytes
    pub fn contract_wasm_hash(&self) -> ContractWasmHash {
        self.contract_wasm_hash
    }

    /// Hash for accessing contract bytes
    pub fn contract_wasm_key(&self) -> Key {
        self.contract_wasm_hash.into()
    }

    /// Returns immutable reference to methods
    pub fn entry_points(&self) -> &EntryPoints {
        &self.entry_points
    }

    /// Takes `named_keys`
    pub fn take_named_keys(self) -> NamedKeys {
        self.named_keys
    }

    /// Returns a reference to `named_keys`
    pub fn named_keys(&self) -> &BTreeMap<String, Key> {
        &self.named_keys
    }

    /// Returns a mutable reference to `named_keys`
    pub fn named_keys_mut(&mut self) -> &mut BTreeMap<String, Key> {
        &mut self.named_keys
    }

    /// Appends `keys` to `named_keys`
    pub fn named_keys_append(&mut self, keys: &mut NamedKeys) {
        self.named_keys.append(keys);
    }

    /// Determines if `Contract` is compatibile with a given `ProtocolVersion`.
    pub fn is_compatible_protocol_version(&self, protocol_version: ProtocolVersion) -> bool {
        self.protocol_version.value().major == protocol_version.value().major
    }
}

impl Default for Contract {
    fn default() -> Self {
        let mut entry_points = BTreeMap::new();
        entry_points.insert("call".into(), EntryPoint::default());
        Contract {
            contract_package_hash: [0; 32],
            contract_wasm_hash: [0; 32],
            named_keys: BTreeMap::new(),
            entry_points,
            protocol_version: ProtocolVersion::V1_0_0,
        }
    }
}

impl ToBytes for Contract {
    fn to_bytes(&self) -> Result<Vec<u8>, bytesrepr::Error> {
        let mut result = self.contract_package_hash.to_bytes()?;
        result.append(&mut ToBytes::to_bytes(&self.contract_wasm_hash)?);
        result.append(&mut ToBytes::to_bytes(&self.entry_points)?);
        result.append(&mut ToBytes::to_bytes(&self.named_keys)?);
        result.append(&mut self.protocol_version.to_bytes()?);
        Ok(result)
    }

    fn serialized_length(&self) -> usize {
        ToBytes::serialized_length(&self.entry_points)
            + ToBytes::serialized_length(&self.contract_package_hash)
            + ToBytes::serialized_length(&self.contract_wasm_hash)
            + ToBytes::serialized_length(&self.protocol_version)
    }
}

impl FromBytes for Contract {
    fn from_bytes(bytes: &[u8]) -> Result<(Self, &[u8]), bytesrepr::Error> {
        let (contract_package_hash, bytes) = <[u8; KEY_HASH_LENGTH]>::from_bytes(bytes)?;
        let (contract_wasm_hash, bytes) = <[u8; KEY_HASH_LENGTH]>::from_bytes(bytes)?;
        let (named_keys, bytes) = BTreeMap::<String, Key>::from_bytes(bytes)?;
        let (entrypoints, bytes) = BTreeMap::<String, EntryPoint>::from_bytes(bytes)?;
        let (protocol_version, bytes) = ProtocolVersion::from_bytes(bytes)?;
        Ok((
            Contract {
                contract_package_hash,
                contract_wasm_hash,
                named_keys,
                entry_points: entrypoints,
                protocol_version,
            },
            bytes,
        ))
    }
}

/// Context of method execution
#[repr(u8)]
#[derive(Debug, Copy, Clone, PartialEq, Eq)]
pub enum EntryPointType {
    /// Runs as session code
    Session = 0,
    /// Runs within contract's context
    Contract = 1,
}

impl ToBytes for EntryPointType {
    fn to_bytes(&self) -> Result<Vec<u8>, bytesrepr::Error> {
        let mut result = bytesrepr::allocate_buffer(self)?;
        result.push(*self as u8);
        Ok(result)
    }

    fn serialized_length(&self) -> usize {
        1
    }
}

impl FromBytes for EntryPointType {
    fn from_bytes(bytes: &[u8]) -> Result<(Self, &[u8]), bytesrepr::Error> {
        let (value, bytes) = u8::from_bytes(bytes)?;
        match value {
            0 => Ok((EntryPointType::Session, bytes)),
            1 => Ok((EntryPointType::Contract, bytes)),
            _ => Err(bytesrepr::Error::Formatting),
        }
    }
}

/// Default name for an entry point
pub const DEFAULT_ENTRY_POINT_NAME: &str = "call";

/// Type signature of a method. Order of arguments matter since can be
/// referenced by index as well as name.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct EntryPoint {
    name: String,
    args: Vec<Parameter>,
    ret: CLType,
    access: EntryPointAccess,
    entry_point_type: EntryPointType,
}

impl
    Into<(
        String,
        Vec<Parameter>,
        CLType,
        EntryPointAccess,
        EntryPointType,
    )> for EntryPoint
{
    fn into(
        self,
    ) -> (
        String,
        Vec<Parameter>,
        CLType,
        EntryPointAccess,
        EntryPointType,
    ) {
        (
            self.name,
            self.args,
            self.ret,
            self.access,
            self.entry_point_type,
        )
    }
}

impl EntryPoint {
    /// `EntryPoint` constructor.
    pub fn new(
        name: String,
        args: Vec<Parameter>,
        ret: CLType,
        access: EntryPointAccess,
        entry_point_type: EntryPointType,
    ) -> Self {
        EntryPoint {
            name,
            args,
            ret,
            access,
            entry_point_type,
        }
    }

    /// Creates default entry point for a contract.
    pub fn default_for_contract() -> Self {
        EntryPoint {
            name: DEFAULT_ENTRY_POINT_NAME.to_string(),
            args: Vec::new(),
            ret: CLType::Unit,
            access: EntryPointAccess::Public,
            entry_point_type: EntryPointType::Contract,
        }
    }

    /// Get name.
    pub fn name(&self) -> &str {
        &self.name
    }

    /// Get access enum.
    pub fn access(&self) -> &EntryPointAccess {
        &self.access
    }

    /// Get the arguments for this method.
    pub fn args(&self) -> &[Parameter] {
        self.args.as_slice()
    }

    /// Obtains entry point
    pub fn entry_point_type(&self) -> EntryPointType {
        self.entry_point_type
    }
}

impl Default for EntryPoint {
    /// constructor for a public session `EntryPoint` that takes no args and returns `Unit`
    fn default() -> Self {
        EntryPoint {
            name: DEFAULT_ENTRY_POINT_NAME.to_string(),
            args: Vec::new(),
            ret: CLType::Unit,
            access: EntryPointAccess::Public,
            entry_point_type: EntryPointType::Session,
        }
    }
}

impl ToBytes for EntryPoint {
    fn to_bytes(&self) -> Result<Vec<u8>, bytesrepr::Error> {
        let mut result = bytesrepr::allocate_buffer(self)?;
        result.append(&mut self.name.to_bytes()?);
        result.append(&mut self.args.to_bytes()?);
        self.ret.append_bytes(&mut result);
        result.append(&mut self.access.to_bytes()?);
        result.append(&mut self.entry_point_type.to_bytes()?);

        Ok(result)
    }

    fn serialized_length(&self) -> usize {
        self.name.serialized_length()
            + self.args.serialized_length()
            + self.ret.serialized_length()
            + self.access.serialized_length()
            + self.entry_point_type.serialized_length()
    }
}

impl FromBytes for EntryPoint {
    fn from_bytes(bytes: &[u8]) -> Result<(Self, &[u8]), bytesrepr::Error> {
        let (name, bytes) = String::from_bytes(bytes)?;
        let (args, bytes) = Vec::<Parameter>::from_bytes(bytes)?;
        let (ret, bytes) = CLType::from_bytes(bytes)?;
        let (access, bytes) = EntryPointAccess::from_bytes(bytes)?;
        let (entry_point_type, bytes) = EntryPointType::from_bytes(bytes)?;

        Ok((
            EntryPoint {
                name,
                args,
                ret,
                access,
                entry_point_type,
            },
            bytes,
        ))
    }
}

/// Enum describing the possible access control options for a contract entry
/// point (method).
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum EntryPointAccess {
    /// Anyone can call this method (no access controls).
    Public,
    /// Only users from the listed groups may call this method. Note: if the
    /// list is empty then this method is not callable from outside the
    /// contract.
    Groups(Vec<Group>),
}

const ENTRYPOINTACCESS_PUBLIC_TAG: u8 = 1;
const ENTRYPOINTACCESS_GROUPS_TAG: u8 = 2;

impl EntryPointAccess {
    /// Constructor for access granted to only listed groups.
    pub fn groups(labels: &[&str]) -> Self {
        let list: Vec<Group> = labels.iter().map(|s| Group(String::from(*s))).collect();
        EntryPointAccess::Groups(list)
    }
}

impl ToBytes for EntryPointAccess {
    fn to_bytes(&self) -> Result<Vec<u8>, bytesrepr::Error> {
        let mut result = bytesrepr::allocate_buffer(self)?;

        match self {
            EntryPointAccess::Public => {
                result.push(ENTRYPOINTACCESS_PUBLIC_TAG);
            }
            EntryPointAccess::Groups(groups) => {
                result.push(ENTRYPOINTACCESS_GROUPS_TAG);
                result.append(&mut groups.to_bytes()?);
            }
        }
        Ok(result)
    }

    fn serialized_length(&self) -> usize {
        match self {
            EntryPointAccess::Public => 1,
            EntryPointAccess::Groups(groups) => 1 + groups.serialized_length(),
        }
    }
}

impl FromBytes for EntryPointAccess {
    fn from_bytes(bytes: &[u8]) -> Result<(Self, &[u8]), bytesrepr::Error> {
        let (tag, bytes) = u8::from_bytes(bytes)?;

        match tag {
            ENTRYPOINTACCESS_PUBLIC_TAG => Ok((EntryPointAccess::Public, bytes)),
            ENTRYPOINTACCESS_GROUPS_TAG => {
                let (groups, bytes) = Vec::<Group>::from_bytes(bytes)?;
                let result = EntryPointAccess::Groups(groups);
                Ok((result, bytes))
            }
            _ => Err(bytesrepr::Error::Formatting),
        }
    }
}

/// Argument to a method
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Parameter {
    name: String,
    cl_type: CLType,
}

impl Parameter {
    /// `Parameter` constructor.
    pub fn new<T: Into<String>>(name: T, cl_type: CLType) -> Self {
        Parameter {
            name: name.into(),
            cl_type,
        }
    }

    /// Get the type of this argument.
    pub fn cl_type(&self) -> &CLType {
        &self.cl_type
    }
}

impl Into<(String, CLType)> for Parameter {
    fn into(self) -> (String, CLType) {
        (self.name, self.cl_type)
    }
}

impl ToBytes for Parameter {
    fn to_bytes(&self) -> Result<Vec<u8>, bytesrepr::Error> {
        let mut result = ToBytes::to_bytes(&self.name)?;
        self.cl_type.append_bytes(&mut result);

        Ok(result)
    }

    fn serialized_length(&self) -> usize {
        ToBytes::serialized_length(&self.name) + self.cl_type.serialized_length()
    }
}

impl FromBytes for Parameter {
    fn from_bytes(bytes: &[u8]) -> Result<(Self, &[u8]), bytesrepr::Error> {
        let (name, bytes) = String::from_bytes(bytes)?;
        let (cl_type, bytes) = CLType::from_bytes(bytes)?;

        Ok((Parameter { name, cl_type }, bytes))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{AccessRights, URef};

    fn make_contract_package() -> ContractPackage {
        let mut contract_package = ContractPackage::new(URef::new([0; 32], AccessRights::NONE));

        // add groups
        {
            let group_urefs = {
                let mut ret = BTreeSet::new();
                ret.insert(URef::new([1; 32], AccessRights::READ));
                ret
            };

            contract_package
                .groups_mut()
                .insert(Group::new("Group 1"), group_urefs.clone());

            contract_package
                .groups_mut()
                .insert(Group::new("Group 2"), group_urefs);
        }

        // add entry_points
        let entry_points = {
            let mut ret = BTreeMap::new();
            let entrypoint = EntryPoint::new(
                "method0".to_string(),
                vec![],
                CLType::U32,
                EntryPointAccess::groups(&["Group 2"]),
                EntryPointType::Session,
            );
            ret.insert(entrypoint.name().to_owned(), entrypoint);
            let entrypoint = EntryPoint::new(
                "method1".to_string(),
                vec![Parameter::new("Foo", CLType::U32)],
                CLType::U32,
                EntryPointAccess::groups(&["Group 1"]),
                EntryPointType::Session,
            );
            ret.insert(entrypoint.name().to_owned(), entrypoint);
            ret
        };

        let contract_package_hash = [41; 32];
        let contract_hash = [42; 32];
        let contract_wasm_hash = [43; 32];
        let named_keys = NamedKeys::new();
        let protocol_version = ProtocolVersion::V1_0_0;

        // _contract = Contract::new(
        //     contract_package_hash,
        //     contract_wasm_hash,
        //     named_keys,
        //     entry_points,
        //     protocol_version,
        // );

        contract_package.insert_contract_version(protocol_version.value().major, contract_hash);

        contract_package
    }

    #[test]
    fn roundtrip_serialization() {
        let contract_metadata = make_contract_package();
        let bytes = contract_metadata.to_bytes().expect("should serialize");
        let (decoded_metadata, rem) =
            ContractPackage::from_bytes(&bytes).expect("should deserialize");
        assert_eq!(contract_metadata, decoded_metadata);
        assert_eq!(rem.len(), 0);
    }

    // #[test]
    // fn should_check_user_group_in_use() {
    //     let mut contract_package = make_contract_package();
    //     assert!(contract_package.is_user_group_in_use(&Group::new("Group 1")));
    //     assert!(contract_package.is_user_group_in_use(&Group::new("Group 2")));
    //     assert!(!contract_package.is_user_group_in_use(&Group::new("Non existing group")));
    //
    //     let contract_version_key = ContractVersionKey::new(1, CONTRACT_INITIAL_VERSION);
    //
    //     contract_package
    //         .disable_contract_version(&contract_version_key)
    //         .expect("should remove version");
    //     assert!(!contract_package.is_user_group_in_use(&Group::new("Group 1")));
    //     assert!(!contract_package.is_user_group_in_use(&Group::new("Group 2")));
    // }

    #[test]
    fn should_remove_group() {
        let mut contract_metadata = make_contract_package();

        assert!(!contract_metadata.remove_group(&Group::new("Non existing group")));
        assert!(!contract_metadata.remove_group(&Group::new("Group 1"))); // Group in use

        contract_metadata
            .disable_contract_version(&ContractVersionKey::new(1, CONTRACT_INITIAL_VERSION))
            .expect("should remove version");

        assert!(contract_metadata.remove_group(&Group::new("Group 1"))); // Group not used used can be removed
        assert!(!contract_metadata.remove_group(&Group::new("Group 1"))); // Group does not exist
    }

    // #[test]
    // fn should_check_group_not_in_use() {
    //     let contract_metadata = ContractPackage::default();
    //     assert!(!contract_metadata.is_user_group_in_use(&Group::new("Group 1")));
    // }
}
