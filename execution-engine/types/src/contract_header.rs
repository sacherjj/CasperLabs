//! Data types for contract metadata (including version and method type signatures)

use crate::{
    bytesrepr::{self, FromBytes, ToBytes},
    uref::URef,
    AccessRights, CLType, Key, ProtocolVersion,
};
use alloc::{
    collections::{BTreeMap, BTreeSet},
    string::String,
    vec::Vec,
};

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

pub type ContractVersion = u8;

pub const CONTRACT_INITIAL_VERSION: ContractVersion = 1;

pub type ProtocolVersionMajor = u32;
pub type ContractLookupKey = (ProtocolVersionMajor, ContractVersion);
pub type ContractVersions = BTreeMap<ContractLookupKey, Contract>;
pub type RemovedVersions = BTreeSet<ContractLookupKey>;

/// Collection of different versions of the same contract.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ContractPackage {
    /// Key used to add or remove versions
    access_key: URef,
    versions: ContractVersions,
    /// Versions that can be called
    // versions: BTreeMap<SemVer, Contract>,
    /// Old versions that are no longer supported
    removed_versions: RemovedVersions,
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

        let key = (
            ProtocolVersion::V1_0_0.value().major,
            CONTRACT_INITIAL_VERSION,
        );
        versions.insert(key, Contract::default());
        ContractPackage {
            access_key: URef::new([0; 32], AccessRights::NONE),
            versions,
            removed_versions: BTreeSet::new(),
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
            removed_versions: BTreeSet::new(),
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
    pub fn get_version(&self, version_lookup_key: ContractLookupKey) -> Option<&Contract> {
        self.versions.get(&version_lookup_key)
    }

    /// Checks if the given version is active
    pub fn is_version_active(&self, version_lookup_key: ContractLookupKey) -> bool {
        self.versions.contains_key(&version_lookup_key)
    }

    /// Modify the collection of active versions to include the given one.
    pub fn add_version(
        &mut self,
        version_lookup_key: ContractLookupKey,
        header: Contract,
    ) -> Result<(), Error> {
        if self.removed_versions.contains(&version_lookup_key)
            || self.versions.contains_key(&version_lookup_key)
        {
            return Err(Error::PreviouslyUsedVersion);
        }

        self.versions.insert(version_lookup_key, header);
        Ok(())
    }

    /// Checks if the given version is removed
    fn is_version_removed(&self, version_lookup_key: ContractLookupKey) -> bool {
        self.removed_versions.get(&version_lookup_key).is_some()
    }

    /// Remove the given version from active versions, putting it into removed versions.
    pub fn remove_version(&mut self, version_lookup_key: ContractLookupKey) -> Result<(), Error> {
        if self.is_version_removed(version_lookup_key) {
            return Ok(());
        } else if !self.get_version(version_lookup_key).is_some() {
            return Err(Error::VersionNotFound);
        }

        self.versions.remove(&version_lookup_key);
        Ok(())
    }

    /// Returns mutable reference to active versions.
    pub fn versions_mut(&mut self) -> &mut ContractVersions {
        &mut self.versions
    }

    /// Consumes contract header and returns a map of active versions.
    pub fn take_versions(self) -> ContractVersions {
        self.versions
    }

    /// Get removed versions set.
    pub fn removed_versions(&self) -> &RemovedVersions {
        &self.removed_versions
    }

    /// Returns mutable versions
    pub fn removed_versions_mut(&mut self) -> &mut RemovedVersions {
        &mut self.removed_versions
    }

    /// Checks is given group is in use in at least one of active contracts.
    fn is_user_group_in_use(&self, group: &Group) -> bool {
        for versions in self.versions.values() {
            for entrypoint in versions.entrypoints().values() {
                if let EntryPointAccess::Groups(groups) = entrypoint.access() {
                    if groups.contains(group) {
                        return true;
                    }
                }
            }
        }
        false
    }

    /// Removes a user group.
    ///
    /// Returns true if group could be removed, and false otherwise if the user group is still
    /// active.
    pub fn remove_group(&mut self, group: &Group) -> bool {
        if self.is_user_group_in_use(group) {
            return false;
        }

        self.groups.remove(group).is_some()
    }

    /// Gets next contract version for given protocol version
    pub fn next_contract_version_for(
        &self,
        protocol_version: ProtocolVersionMajor,
    ) -> ContractVersion {
        self.versions
            .keys()
            .filter_map(|&(version, contract_version)| {
                if version == protocol_version {
                    Some(contract_version)
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
        result.append(&mut self.removed_versions.to_bytes()?);
        result.append(&mut self.groups.to_bytes()?);

        Ok(result)
    }

    fn serialized_length(&self) -> usize {
        self.access_key.serialized_length()
            + self.versions.serialized_length()
            + self.removed_versions.serialized_length()
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
            removed_versions,
            groups,
        };

        Ok((result, bytes))
    }
}

pub type Entrypoints = BTreeMap<String, EntryPoint>;
pub type NamedKeys = BTreeMap<String, Key>;

/// Methods and type signatures supported by a contract.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Contract {
    contract_key: Key,
    named_keys: NamedKeys,
    entrypoints: Entrypoints,
    protocol_version: ProtocolVersion,
}

impl Contract {
    /// `ContractHeader` constructor.
    pub fn new(
        contract_key: Key,
        named_keys: NamedKeys,
        methods: Entrypoints,
        protocol_version: ProtocolVersion,
    ) -> Self {
        Contract {
            contract_key,
            named_keys,
            entrypoints: methods,
            protocol_version,
        }
    }

    /// Checks whether there is a method with the given name
    pub fn has_method_name(&self, name: &str) -> bool {
        self.entrypoints.contains_key(name)
    }

    /// Returns the list of method namesget_version
    pub fn method_names(&self) -> Vec<&str> {
        self.entrypoints.keys().map(|s| s.as_str()).collect()
    }

    /// Returns the type signature for the given `method`.
    pub fn get_method(&self, method: &str) -> Option<&EntryPoint> {
        self.entrypoints.get(method)
    }

    /// Get the protocol version this header is targeting.
    pub fn protocol_version(&self) -> ProtocolVersion {
        self.protocol_version
    }

    /// Adds new entry point
    pub fn add_entrypoint<T: Into<String>>(&mut self, name: T, entrypoint: EntryPoint) {
        self.entrypoints.insert(name.into(), entrypoint);
    }

    /// Hash for accessing contract version
    pub fn contract_key(&self) -> Key {
        self.contract_key
    }

    /// Hash for accessing contract version
    pub fn take_entrypoints(self) -> Entrypoints {
        self.entrypoints
    }

    /// Returns immutable reference to methods
    pub fn entrypoints(&self) -> &Entrypoints {
        &self.entrypoints
    }

    pub fn take_named_keys(self) -> NamedKeys {
        self.named_keys
    }
    pub fn named_keys(&mut self) -> &NamedKeys {
        &self.named_keys
    }
    pub fn named_keys_append(&mut self, keys: &mut NamedKeys) {
        self.named_keys.append(keys);
    }
}

impl Default for Contract {
    fn default() -> Self {
        let mut methods = BTreeMap::new();
        methods.insert("call".into(), EntryPoint::default());
        Contract {
            contract_key: Key::Hash([0; 32]),
            named_keys: BTreeMap::new(),
            entrypoints: methods,
            protocol_version: ProtocolVersion::V1_0_0,
        }
    }
}

impl ToBytes for Contract {
    fn to_bytes(&self) -> Result<Vec<u8>, bytesrepr::Error> {
        let mut result = self.contract_key.to_bytes()?;
        result.append(&mut ToBytes::to_bytes(&self.entrypoints)?);
        result.append(&mut ToBytes::to_bytes(&self.named_keys)?);
        result.append(&mut self.protocol_version.to_bytes()?);
        Ok(result)
    }

    fn serialized_length(&self) -> usize {
        ToBytes::serialized_length(&self.entrypoints)
            + ToBytes::serialized_length(&self.contract_key)
            + ToBytes::serialized_length(&self.protocol_version)
    }
}

impl FromBytes for Contract {
    fn from_bytes(bytes: &[u8]) -> Result<(Self, &[u8]), bytesrepr::Error> {
        let (contract_key, bytes) = Key::from_bytes(bytes)?;
        let (named_keys, bytes) = BTreeMap::<String, Key>::from_bytes(bytes)?;
        let (methods, bytes) = BTreeMap::<String, EntryPoint>::from_bytes(bytes)?;
        let (protocol_version, bytes) = ProtocolVersion::from_bytes(bytes)?;
        Ok((
            Contract {
                contract_key,
                named_keys,
                entrypoints: methods,
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

/// Type signature of a method. Order of arguments matter since can be
/// referenced by index as well as name.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct EntryPoint {
    args: Vec<Parameter>,
    ret: CLType,
    access: EntryPointAccess,
    entry_point_type: EntryPointType,
}

impl Into<(Vec<Parameter>, CLType, EntryPointAccess, EntryPointType)> for EntryPoint {
    fn into(self) -> (Vec<Parameter>, CLType, EntryPointAccess, EntryPointType) {
        (self.args, self.ret, self.access, self.entry_point_type)
    }
}

impl EntryPoint {
    /// `EntryPoint` constructor.
    pub fn new(
        args: Vec<Parameter>,
        ret: CLType,
        access: EntryPointAccess,
        entry_point_type: EntryPointType,
    ) -> Self {
        EntryPoint {
            args,
            ret,
            access,
            entry_point_type,
        }
    }

    /// constructor for a public session `EntryPoint` that takes no args and returns `Unit`
    pub fn thunk() -> Self {
        EntryPoint {
            args: Vec::new(),
            ret: CLType::Unit,
            access: EntryPointAccess::Public,
            entry_point_type: EntryPointType::Session,
        }
    }

    /// Creates default entry point for a contract.
    pub fn default_for_contract() -> Self {
        EntryPoint {
            args: Vec::new(),
            ret: CLType::Unit,
            access: EntryPointAccess::Public,
            entry_point_type: EntryPointType::Contract,
        }
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
    fn default() -> Self {
        EntryPoint {
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

        result.append(&mut self.args.to_bytes()?);
        self.ret.append_bytes(&mut result);
        result.append(&mut self.access.to_bytes()?);
        result.append(&mut self.entry_point_type.to_bytes()?);

        Ok(result)
    }

    fn serialized_length(&self) -> usize {
        self.args.serialized_length()
            + self.ret.serialized_length()
            + self.access.serialized_length()
            + self.entry_point_type.serialized_length()
    }
}

impl FromBytes for EntryPoint {
    fn from_bytes(bytes: &[u8]) -> Result<(Self, &[u8]), bytesrepr::Error> {
        let (args, bytes) = Vec::<Parameter>::from_bytes(bytes)?;
        let (ret, bytes) = CLType::from_bytes(bytes)?;
        let (access, bytes) = EntryPointAccess::from_bytes(bytes)?;
        let (entry_point_type, bytes) = EntryPointType::from_bytes(bytes)?;

        Ok((
            EntryPoint {
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

    fn make_contract_metadata() -> ContractPackage {
        let mut contract_metadata = ContractPackage::new(URef::new([0; 32], AccessRights::NONE));

        let uref1 = URef::new([1; 32], AccessRights::READ);

        let mut set = BTreeSet::new();
        set.insert(uref1);
        contract_metadata
            .groups_mut()
            .insert(Group::new("Group 1"), set.clone());

        contract_metadata
            .groups_mut()
            .insert(Group::new("Group 2"), set);

        let mut methods = BTreeMap::new();

        let entrypoint = EntryPoint::new(
            vec![],
            CLType::U32,
            EntryPointAccess::groups(&["Group 2"]),
            EntryPointType::Session,
        );
        methods.insert("method0".into(), entrypoint);

        let entrypoint = EntryPoint::new(
            vec![Parameter::new("Foo", CLType::U32)],
            CLType::U32,
            EntryPointAccess::groups(&["Group 1"]),
            EntryPointType::Session,
        );
        methods.insert("method1".into(), entrypoint);

        let header = Contract::new(methods, Key::Hash([42; 32]), ProtocolVersion::V1_0_0);
        contract_metadata
            .add_version(CONTRACT_INITIAL_VERSION, header)
            .expect("should add version");

        contract_metadata
    }

    #[test]
    fn roundtrip_serialization() {
        let contract_metadata = make_contract_metadata();
        let bytes = contract_metadata.to_bytes().expect("should serialize");
        let (decoded_metadata, rem) =
            ContractPackage::from_bytes(&bytes).expect("should deserialize");
        assert_eq!(contract_metadata, decoded_metadata);
        assert_eq!(rem.len(), 0);
    }

    #[test]
    fn should_check_user_group_in_use() {
        let mut contract_metadata = make_contract_metadata();
        assert!(contract_metadata.is_user_group_in_use(&Group::new("Group 1")));
        assert!(contract_metadata.is_user_group_in_use(&Group::new("Group 2")));
        assert!(!contract_metadata.is_user_group_in_use(&Group::new("Non existing group")));

        contract_metadata
            .remove_version(CONTRACT_INITIAL_VERSION)
            .expect("should remove version");
        assert!(!contract_metadata.is_user_group_in_use(&Group::new("Group 1")));
        assert!(!contract_metadata.is_user_group_in_use(&Group::new("Group 2")));
    }

    #[test]
    fn should_remove_group() {
        let mut contract_metadata = make_contract_metadata();

        assert!(!contract_metadata.remove_group(&Group::new("Non existing group")));
        assert!(!contract_metadata.remove_group(&Group::new("Group 1"))); // Group in use

        contract_metadata
            .remove_version(CONTRACT_INITIAL_VERSION)
            .expect("should remove version");

        assert!(contract_metadata.remove_group(&Group::new("Group 1"))); // Group not used used can be removed
        assert!(!contract_metadata.remove_group(&Group::new("Group 1"))); // Group does not exist
    }

    #[test]
    fn should_check_group_not_in_use() {
        let contract_metadata = ContractPackage::default();
        assert!(!contract_metadata.is_user_group_in_use(&Group::new("Group 1")));
    }
}
