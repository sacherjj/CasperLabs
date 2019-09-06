use crate::bytesrepr::{Error, FromBytes, ToBytes, U32_SIZE, U64_SIZE, U8_SIZE};
use crate::key::{addr_to_hex, Key, UREF_SIZE};
use crate::uref::{AccessRights, URef, UREF_SIZE_SERIALIZED};
use alloc::collections::{btree_map::BTreeMap, btree_set::BTreeSet};
use alloc::string::String;
use alloc::vec::Vec;
use core::convert::TryFrom;
use core::fmt::{Debug, Display, Formatter};
use failure::Fail;

const DEFAULT_NONCE: u64 = 0;
const DEFAULT_CURRENT_BLOCK_TIME: BlockTime = BlockTime(0);
const DEFAULT_INACTIVITY_PERIOD_TIME: BlockTime = BlockTime(100);

pub const PURSE_ID_SIZE_SERIALIZED: usize = UREF_SIZE_SERIALIZED;

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

/// Thresholds that has to be met when executing an action of certain type.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ActionThresholds {
    deployment: Weight,
    key_management: Weight,
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

impl ActionThresholds {
    /// Creates new ActionThresholds object with provided weights
    ///
    /// Requires deployment threshold to be lower than or equal to
    /// key management threshold.
    pub fn new(
        deployment: Weight,
        key_management: Weight,
    ) -> Result<ActionThresholds, SetThresholdFailure> {
        if deployment > key_management {
            return Err(SetThresholdFailure::DeploymentThresholdError);
        }
        Ok(ActionThresholds {
            deployment,
            key_management,
        })
    }
    /// Sets new threshold for [ActionType::Deployment].
    /// Should return an error if setting new threshold for `action_type` breaks
    /// one of the invariants. Currently, invariant is that
    /// `ActionType::Deployment` threshold shouldn't be higher than any
    /// other, which should be checked both when increasing `Deployment`
    /// threshold and decreasing the other.
    pub fn set_deployment_threshold(
        &mut self,
        new_threshold: Weight,
    ) -> Result<(), SetThresholdFailure> {
        if new_threshold > self.key_management {
            Err(SetThresholdFailure::DeploymentThresholdError)
        } else {
            self.deployment = new_threshold;
            Ok(())
        }
    }

    /// Sets new threshold for [ActionType::KeyManagement].
    pub fn set_key_management_threshold(
        &mut self,
        new_threshold: Weight,
    ) -> Result<(), SetThresholdFailure> {
        if self.deployment > new_threshold {
            Err(SetThresholdFailure::KeyManagementThresholdError)
        } else {
            self.key_management = new_threshold;
            Ok(())
        }
    }

    pub fn deployment(&self) -> &Weight {
        &self.deployment
    }

    pub fn key_management(&self) -> &Weight {
        &self.key_management
    }

    /// Unified function that takes an action type, and changes appropriate
    /// threshold defined by the [ActionType] variants.
    pub fn set_threshold(
        &mut self,
        action_type: ActionType,
        new_threshold: Weight,
    ) -> Result<(), SetThresholdFailure> {
        match action_type {
            ActionType::Deployment => self.set_deployment_threshold(new_threshold),
            ActionType::KeyManagement => self.set_key_management_threshold(new_threshold),
        }
    }
}

impl Default for ActionThresholds {
    fn default() -> Self {
        ActionThresholds {
            deployment: Weight::new(1),
            key_management: Weight::new(1),
        }
    }
}

#[derive(Clone, Copy, Default, Debug, PartialEq, Eq, PartialOrd)]
pub struct BlockTime(pub u64);

/// Holds information about last usage time of specific action.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct AccountActivity {
    // Last time `KeyManagementAction` was used.
    key_management_last_used: BlockTime,
    // Last time `Deployment` action was used.
    deployment_last_used: BlockTime,
    // Inactivity period set for the account.
    inactivity_period_limit: BlockTime,
}

impl AccountActivity {
    // TODO: We need default for inactivity_period_limit.
    // `current_block_time` value is passed in from the node and is coming from the
    // parent block. [inactivity_period_limit] block time period after which
    // account is eligible for recovery.
    pub fn new(
        current_block_time: BlockTime,
        inactivity_period_limit: BlockTime,
    ) -> AccountActivity {
        AccountActivity {
            key_management_last_used: current_block_time,
            deployment_last_used: current_block_time,
            inactivity_period_limit,
        }
    }

    pub fn update_key_management_last_used(&mut self, last_used: BlockTime) {
        self.key_management_last_used = last_used;
    }

    pub fn update_deployment_last_used(&mut self, last_used: BlockTime) {
        self.deployment_last_used = last_used;
    }

    pub fn update_inactivity_period_limit(&mut self, new_inactivity_period_limit: BlockTime) {
        self.inactivity_period_limit = new_inactivity_period_limit;
    }

    pub fn key_management_last_used(&self) -> BlockTime {
        self.key_management_last_used
    }

    pub fn deployment_last_used(&self) -> BlockTime {
        self.deployment_last_used
    }

    pub fn inactivity_period_limit(&self) -> BlockTime {
        self.inactivity_period_limit
    }
}

pub const KEY_SIZE: usize = 32;
/// Maximum number of associated keys.
/// Value chosen arbitrary, shouldn't be too large to prevent bloating
/// `associated_keys` table.
pub const MAX_KEYS: usize = 10;

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

pub const WEIGHT_SIZE: usize = U8_SIZE;

#[derive(PartialOrd, Ord, PartialEq, Eq, Hash, Clone, Copy)]
pub struct PublicKey([u8; KEY_SIZE]);

impl Display for PublicKey {
    fn fmt(&self, f: &mut Formatter) -> core::fmt::Result {
        write!(f, "PublicKey({})", addr_to_hex(&self.0))
    }
}

impl Debug for PublicKey {
    fn fmt(&self, f: &mut Formatter) -> core::fmt::Result {
        write!(f, "{}", self)
    }
}

// TODO: This needs to be updated, `PUBLIC_KEY_SIZE` is not 32 bytes as KEY_SIZE
// * U8_SIZE. I am not changing that as I don't want to deal with ripple effect.

// Public key is encoded as its underlying [u8; 32] array, which in turn
// is serialized as u8 + [u8; 32], u8 represents the length and then 32 element
// array.
pub const PUBLIC_KEY_SIZE: usize = KEY_SIZE * U8_SIZE;

impl PublicKey {
    pub fn new(key: [u8; KEY_SIZE]) -> PublicKey {
        PublicKey(key)
    }

    pub fn value(self) -> [u8; KEY_SIZE] {
        self.0
    }
}

impl From<[u8; KEY_SIZE]> for PublicKey {
    fn from(key: [u8; KEY_SIZE]) -> Self {
        PublicKey(key)
    }
}

impl TryFrom<&[u8]> for PublicKey {
    type Error = TryFromSliceForPublicKeyError;
    fn try_from(bytes: &[u8]) -> Result<Self, Self::Error> {
        if bytes.len() != KEY_SIZE {
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
        let (key_bytes, rem): ([u8; KEY_SIZE], &[u8]) = FromBytes::from_bytes(bytes)?;
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

#[derive(Default, PartialOrd, Ord, PartialEq, Eq, Clone, Debug)]
pub struct AssociatedKeys(BTreeMap<PublicKey, Weight>);

impl AssociatedKeys {
    pub fn empty() -> AssociatedKeys {
        AssociatedKeys(BTreeMap::new())
    }

    pub fn new(key: PublicKey, weight: Weight) -> AssociatedKeys {
        let mut bt: BTreeMap<PublicKey, Weight> = BTreeMap::new();
        bt.insert(key, weight);
        AssociatedKeys(bt)
    }

    /// Adds new AssociatedKey to the set.
    /// Returns true if added successfully, false otherwise.
    #[allow(clippy::map_entry)]
    pub fn add_key(&mut self, key: PublicKey, weight: Weight) -> Result<(), AddKeyFailure> {
        if self.0.len() == MAX_KEYS {
            Err(AddKeyFailure::MaxKeysLimit)
        } else if self.0.contains_key(&key) {
            Err(AddKeyFailure::DuplicateKey)
        } else {
            self.0.insert(key, weight);
            Ok(())
        }
    }

    /// Removes key from the associated keys set.
    /// Returns true if value was found in the set prior to the removal, false
    /// otherwise.
    pub fn remove_key(&mut self, key: &PublicKey) -> Result<(), RemoveKeyFailure> {
        self.0
            .remove(key)
            .map(|_| ())
            .ok_or(RemoveKeyFailure::MissingKey)
    }

    /// Adds new AssociatedKey to the set.
    /// Returns true if added successfully, false otherwise.
    #[allow(clippy::map_entry)]
    pub fn update_key(&mut self, key: PublicKey, weight: Weight) -> Result<(), UpdateKeyFailure> {
        if !self.0.contains_key(&key) {
            return Err(UpdateKeyFailure::MissingKey);
        }

        self.0.insert(key, weight);
        Ok(())
    }

    pub fn get(&self, key: &PublicKey) -> Option<&Weight> {
        self.0.get(key)
    }

    pub fn contains_key(&self, key: &PublicKey) -> bool {
        self.0.contains_key(key)
    }

    pub fn iter(&self) -> impl Iterator<Item = (&PublicKey, &Weight)> {
        self.0.iter()
    }

    /// Helper method that calculates weight for keys that comes from any
    /// source.
    ///
    /// This method is not concerned about uniqueness of the passed iterable.
    /// Uniqueness is determined based on the input collection properties,
    /// which is either BTreeSet (in `[AssociatedKeys::calculate_keys_weight]`)
    /// or BTreeMap (in `[AssociatedKeys::total_keys_weight]`).
    fn calculate_any_keys_weight<'a>(&self, keys: impl Iterator<Item = &'a PublicKey>) -> Weight {
        let total = keys
            .filter_map(|key| self.0.get(key))
            .fold(0u8, |acc, w| acc.saturating_add(w.value()));

        Weight::new(total)
    }

    /// Calculates total weight of authorization keys provided by an argument
    pub fn calculate_keys_weight(&self, authorization_keys: &BTreeSet<PublicKey>) -> Weight {
        self.calculate_any_keys_weight(authorization_keys.iter())
    }

    /// Calculates total weight of all authorization keys
    pub fn total_keys_weight(&self) -> Weight {
        self.calculate_any_keys_weight(self.0.keys())
    }
}

#[derive(PartialEq, Eq, Clone, Debug)]
pub struct Account {
    public_key: [u8; 32],
    nonce: u64,
    known_urefs: BTreeMap<String, Key>,
    purse_id: PurseId,
    associated_keys: AssociatedKeys,
    action_thresholds: ActionThresholds,
    account_activity: AccountActivity,
}

impl Account {
    pub fn new(
        public_key: [u8; 32],
        nonce: u64,
        known_urefs: BTreeMap<String, Key>,
        purse_id: PurseId,
        associated_keys: AssociatedKeys,
        action_thresholds: ActionThresholds,
        account_activity: AccountActivity,
    ) -> Self {
        Account {
            public_key,
            nonce,
            known_urefs,
            purse_id,
            associated_keys,
            action_thresholds,
            account_activity,
        }
    }

    pub fn create(
        account_addr: [u8; 32],
        known_urefs: BTreeMap<String, Key>,
        purse_id: PurseId,
    ) -> Self {
        let nonce = DEFAULT_NONCE;
        let associated_keys = AssociatedKeys::new(PublicKey::new(account_addr), Weight::new(1));
        let action_thresholds: ActionThresholds = Default::default();
        let account_activity =
            AccountActivity::new(DEFAULT_CURRENT_BLOCK_TIME, DEFAULT_INACTIVITY_PERIOD_TIME);
        Account::new(
            account_addr,
            nonce,
            known_urefs,
            purse_id,
            associated_keys,
            action_thresholds,
            account_activity,
        )
    }

    pub fn insert_urefs(&mut self, keys: &mut BTreeMap<String, Key>) {
        self.known_urefs.append(keys);
    }

    pub fn urefs_lookup(&self) -> &BTreeMap<String, Key> {
        &self.known_urefs
    }

    pub fn get_urefs_lookup_mut(&mut self) -> &mut BTreeMap<String, Key> {
        &mut self.known_urefs
    }

    pub fn pub_key(&self) -> [u8; 32] {
        self.public_key
    }

    pub fn purse_id(&self) -> PurseId {
        self.purse_id
    }

    /// Returns an [`AccessRights::ADD`]-only version of the [`PurseId`].
    pub fn purse_id_add_only(&self) -> PurseId {
        let purse_id_uref = self.purse_id.value();
        let add_only_uref = URef::new(purse_id_uref.addr(), AccessRights::ADD);
        PurseId::new(add_only_uref)
    }

    pub fn get_associated_keys(&self) -> impl Iterator<Item = (&PublicKey, &Weight)> {
        self.associated_keys.iter()
    }

    pub fn action_thresholds(&self) -> &ActionThresholds {
        &self.action_thresholds
    }

    pub fn account_activity(&self) -> &AccountActivity {
        &self.account_activity
    }

    pub fn nonce(&self) -> u64 {
        self.nonce
    }

    /// Consumes instance of account and returns new one
    /// with old contents but with nonce increased by 1.
    pub fn increment_nonce(&mut self) {
        self.nonce += 1;
    }

    pub fn add_associated_key(
        &mut self,
        public_key: PublicKey,
        weight: Weight,
    ) -> Result<(), AddKeyFailure> {
        self.associated_keys.add_key(public_key, weight)
    }

    /// Checks if subtracting passed weight from current total would make the
    /// new cumulative weight to fall below any of the thresholds on account.
    fn check_thresholds_for_weight_update(&self, weight: Weight) -> bool {
        let total_weight = self.associated_keys.total_keys_weight();

        // Safely calculate new weight
        let new_weight = total_weight.value().saturating_sub(weight.value());

        // Returns true if the new weight would be greater or equal to all of
        // the thresholds.
        new_weight >= self.action_thresholds().deployment().value()
            && new_weight >= self.action_thresholds().key_management().value()
    }

    pub fn remove_associated_key(&mut self, public_key: PublicKey) -> Result<(), RemoveKeyFailure> {
        if let Some(weight) = self.associated_keys.get(&public_key) {
            // Check if removing this weight would fall below thresholds
            if !self.check_thresholds_for_weight_update(*weight) {
                return Err(RemoveKeyFailure::ThresholdViolation);
            }
        }
        self.associated_keys.remove_key(&public_key)
    }

    pub fn update_associated_key(
        &mut self,
        public_key: PublicKey,
        weight: Weight,
    ) -> Result<(), UpdateKeyFailure> {
        if let Some(current_weight) = self.associated_keys.get(&public_key) {
            if weight < *current_weight {
                let diff = Weight::new(current_weight.value() - weight.value());
                // New weight is smaller than current weight
                if !self.check_thresholds_for_weight_update(diff) {
                    return Err(UpdateKeyFailure::ThresholdViolation);
                }
            }
        }
        self.associated_keys.update_key(public_key, weight)
    }

    pub fn get_associated_key_weight(&self, public_key: PublicKey) -> Option<&Weight> {
        self.associated_keys.get(&public_key)
    }

    pub fn set_action_threshold(
        &mut self,
        action_type: ActionType,
        weight: Weight,
    ) -> Result<(), SetThresholdFailure> {
        // Verify if new threshold weight exceeds total weight of allassociated
        // keys.
        self.can_set_threshold(weight)?;
        // Set new weight for given action
        self.action_thresholds.set_threshold(action_type, weight)
    }

    /// Verifies if user can set action threshold
    pub fn can_set_threshold(&self, new_threshold: Weight) -> Result<(), SetThresholdFailure> {
        let total_weight = self.associated_keys.total_keys_weight();
        if new_threshold > total_weight {
            return Err(SetThresholdFailure::InsufficientTotalWeight);
        }
        Ok(())
    }

    /// Checks whether all authorization keys are associated with this account
    pub fn can_authorize(&self, authorization_keys: &BTreeSet<PublicKey>) -> bool {
        authorization_keys
            .iter()
            .all(|e| self.associated_keys.contains_key(e))
    }

    /// Checks whether the sum of the weights of all authorization keys is
    /// greater or equal to deploy threshold.
    pub fn can_deploy_with(&self, authorization_keys: &BTreeSet<PublicKey>) -> bool {
        let total_weight = self
            .associated_keys
            .calculate_keys_weight(authorization_keys);

        total_weight >= *self.action_thresholds().deployment()
    }

    /// Checks whether the sum of the weights of all authorization keys is
    /// greater or equal to key management threshold.
    pub fn can_manage_keys_with(&self, authorization_keys: &BTreeSet<PublicKey>) -> bool {
        let total_weight = self
            .associated_keys
            .calculate_keys_weight(authorization_keys);

        total_weight >= *self.action_thresholds().key_management()
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

impl ToBytes for AssociatedKeys {
    fn to_bytes(&self) -> Result<Vec<u8>, Error> {
        ToBytes::to_bytes(&self.0)
    }
}

impl FromBytes for AssociatedKeys {
    fn from_bytes(bytes: &[u8]) -> Result<(Self, &[u8]), Error> {
        let (keys_map, rem): (BTreeMap<PublicKey, Weight>, &[u8]) = FromBytes::from_bytes(bytes)?;
        let mut keys = AssociatedKeys::empty();
        keys_map.into_iter().for_each(|(k, v)| {
            // NOTE: we're ignoring potential errors (duplicate key, maximum number of
            // elements). This is safe, for now, as we were the ones that
            // serialized `AssociatedKeys` in the first place.
            keys.add_key(k, v).unwrap();
        });
        Ok((keys, rem))
    }
}

pub const BLOCKTIME_SER_SIZE: usize = U64_SIZE;

impl ToBytes for BlockTime {
    fn to_bytes(&self) -> Result<Vec<u8>, Error> {
        self.0.to_bytes()
    }
}

impl FromBytes for BlockTime {
    fn from_bytes(bytes: &[u8]) -> Result<(Self, &[u8]), Error> {
        let (time, rem) = FromBytes::from_bytes(bytes)?;
        Ok((BlockTime(time), rem))
    }
}

const DEPLOYMENT_THRESHOLD_ID: u8 = 0;
const KEY_MANAGEMENT_THRESHOLD_ID: u8 = 1;

impl ToBytes for ActionThresholds {
    fn to_bytes(&self) -> Result<Vec<u8>, Error> {
        let mut result = Vec::with_capacity(2 * (WEIGHT_SIZE + U8_SIZE));
        result.push(DEPLOYMENT_THRESHOLD_ID);
        result.extend(&self.deployment.to_bytes()?);
        result.push(KEY_MANAGEMENT_THRESHOLD_ID);
        result.extend(&self.key_management.to_bytes()?);
        Ok(result)
    }
}

impl FromBytes for ActionThresholds {
    fn from_bytes(bytes: &[u8]) -> Result<(Self, &[u8]), Error> {
        let mut action_thresholds: ActionThresholds = Default::default();
        let (id_1, rem): (u8, &[u8]) = FromBytes::from_bytes(bytes)?;
        let (weight_1, rem2): (Weight, &[u8]) = FromBytes::from_bytes(&rem)?;
        let (id_2, rem3): (u8, &[u8]) = FromBytes::from_bytes(&rem2)?;
        let (weight_2, rem4): (Weight, &[u8]) = FromBytes::from_bytes(&rem3)?;
        match (id_1, id_2) {
            (DEPLOYMENT_THRESHOLD_ID, KEY_MANAGEMENT_THRESHOLD_ID) => {
                action_thresholds
                    .set_key_management_threshold(weight_2)
                    .map_err(Error::custom)?;
                action_thresholds
                    .set_deployment_threshold(weight_1)
                    .map_err(Error::custom)?;
                Ok((action_thresholds, rem4))
            }
            (KEY_MANAGEMENT_THRESHOLD_ID, DEPLOYMENT_THRESHOLD_ID) => {
                action_thresholds
                    .set_key_management_threshold(weight_1)
                    .map_err(Error::custom)?;
                action_thresholds
                    .set_deployment_threshold(weight_2)
                    .map_err(Error::custom)?;
                Ok((action_thresholds, rem4))
            }
            _ => Err(Error::FormattingError),
        }
    }
}

const KEY_MANAGEMENT_LAST_USED_ID: u8 = 0;
const DEPLOYMENT_LAST_USED_ID: u8 = 1;
const INACTIVITY_PERIOD_LIMIT_ID: u8 = 2;

impl ToBytes for AccountActivity {
    fn to_bytes(&self) -> Result<Vec<u8>, Error> {
        let mut result = Vec::with_capacity(3 * (BLOCKTIME_SER_SIZE + U8_SIZE));
        result.push(KEY_MANAGEMENT_LAST_USED_ID);
        result.extend(&self.key_management_last_used.to_bytes()?);
        result.push(DEPLOYMENT_LAST_USED_ID);
        result.extend(&self.deployment_last_used.to_bytes()?);
        result.push(INACTIVITY_PERIOD_LIMIT_ID);
        result.extend(&self.inactivity_period_limit.to_bytes()?);
        Ok(result)
    }
}

fn account_activity_parser_helper<'a>(
    acc_activity: &mut AccountActivity,
    bytes: &'a [u8],
) -> Result<&'a [u8], Error> {
    let (id, rem) = FromBytes::from_bytes(bytes)?;
    let (block_time, rest): (BlockTime, &[u8]) = FromBytes::from_bytes(rem)?;
    match id {
        KEY_MANAGEMENT_LAST_USED_ID => acc_activity.update_key_management_last_used(block_time),
        DEPLOYMENT_LAST_USED_ID => acc_activity.update_deployment_last_used(block_time),
        INACTIVITY_PERIOD_LIMIT_ID => acc_activity.update_inactivity_period_limit(block_time),
        _ => return Err(Error::FormattingError),
    };
    Ok(rest)
}

impl FromBytes for AccountActivity {
    fn from_bytes(bytes: &[u8]) -> Result<(Self, &[u8]), Error> {
        let mut acc_activity = AccountActivity::new(BlockTime(0), BlockTime(0));
        let rem = account_activity_parser_helper(&mut acc_activity, bytes)?;
        let rem2 = account_activity_parser_helper(&mut acc_activity, rem)?;
        let rem3 = account_activity_parser_helper(&mut acc_activity, rem2)?;
        Ok((acc_activity, rem3))
    }
}

impl ToBytes for Account {
    fn to_bytes(&self) -> Result<Vec<u8>, Error> {
        let action_thresholds_size = 2 * (WEIGHT_SIZE + U8_SIZE);
        let account_activity_size: usize = 3 * (BLOCKTIME_SER_SIZE + U8_SIZE);
        let associated_keys_size =
            self.associated_keys.0.len() * (PUBLIC_KEY_SIZE + WEIGHT_SIZE) + U32_SIZE;
        let known_urefs_size = UREF_SIZE * self.known_urefs.len() + U32_SIZE;
        let purse_id_size = UREF_SIZE;
        let serialized_account_size = KEY_SIZE // pub key
            + U64_SIZE // nonce
            + known_urefs_size
            + purse_id_size
            + associated_keys_size
            + action_thresholds_size
            + account_activity_size;
        if serialized_account_size >= u32::max_value() as usize {
            return Err(Error::OutOfMemoryError);
        }
        let mut result: Vec<u8> = Vec::with_capacity(serialized_account_size);
        result.extend(&self.public_key.to_bytes()?);
        result.append(&mut self.nonce.to_bytes()?);
        result.append(&mut self.known_urefs.to_bytes()?);
        result.append(&mut self.purse_id.value().to_bytes()?);
        result.append(&mut self.associated_keys.to_bytes()?);
        result.append(&mut self.action_thresholds.to_bytes()?);
        result.append(&mut self.account_activity.to_bytes()?);
        Ok(result)
    }
}

impl FromBytes for Account {
    fn from_bytes(bytes: &[u8]) -> Result<(Self, &[u8]), Error> {
        let (public_key, rem): ([u8; 32], &[u8]) = FromBytes::from_bytes(bytes)?;
        let (nonce, rem): (u64, &[u8]) = FromBytes::from_bytes(rem)?;
        let (known_urefs, rem): (BTreeMap<String, Key>, &[u8]) = FromBytes::from_bytes(rem)?;
        let (purse_id, rem): (URef, &[u8]) = FromBytes::from_bytes(rem)?;
        let (associated_keys, rem): (AssociatedKeys, &[u8]) = FromBytes::from_bytes(rem)?;
        let (action_thresholds, rem): (ActionThresholds, &[u8]) = FromBytes::from_bytes(rem)?;
        let (account_activity, rem): (AccountActivity, &[u8]) = FromBytes::from_bytes(rem)?;
        let purse_id = PurseId::new(purse_id);
        Ok((
            Account {
                public_key,
                nonce,
                known_urefs,
                purse_id,
                associated_keys,
                action_thresholds,
                account_activity,
            },
            rem,
        ))
    }
}

#[cfg(test)]
mod tests {
    use crate::uref::{AccessRights, URef};
    use crate::value::account::{
        Account, AccountActivity, ActionThresholds, ActionType, AddKeyFailure, AssociatedKeys,
        BlockTime, PublicKey, PurseId, RemoveKeyFailure, SetThresholdFailure, UpdateKeyFailure,
        Weight, KEY_SIZE, MAX_KEYS,
    };
    use alloc::collections::{btree_map::BTreeMap, btree_set::BTreeSet};
    use alloc::vec::Vec;
    use core::convert::TryFrom;
    use core::iter::FromIterator;

    #[test]
    fn incremented_nonce() {
        let mut account = Account::new(
            [0u8; 32],
            0,
            BTreeMap::new(),
            PurseId::new(URef::new([0u8; 32], AccessRights::READ_ADD_WRITE)),
            AssociatedKeys::new(PublicKey::new([0u8; 32]), Weight::new(1)),
            Default::default(),
            AccountActivity::new(BlockTime(0), BlockTime(0)),
        );
        assert_eq!(account.nonce(), 0);
        account.increment_nonce();
        assert_eq!(account.nonce(), 1);
    }

    #[test]
    fn associated_keys_add() {
        let mut keys = AssociatedKeys::new([0u8; KEY_SIZE].into(), Weight::new(1));
        let new_pk = PublicKey([1u8; KEY_SIZE]);
        let new_pk_weight = Weight::new(2);
        assert!(keys.add_key(new_pk, new_pk_weight).is_ok());
        assert_eq!(keys.get(&new_pk), Some(&new_pk_weight))
    }

    #[test]
    fn associated_keys_add_full() {
        let map = (0..MAX_KEYS).map(|k| (PublicKey([k as u8; KEY_SIZE]), Weight::new(k as u8)));
        assert_eq!(map.len(), 10);
        let mut keys = {
            let mut tmp = AssociatedKeys::empty();
            map.for_each(|(key, weight)| assert!(tmp.add_key(key, weight).is_ok()));
            tmp
        };
        assert_eq!(
            keys.add_key(PublicKey([100u8; KEY_SIZE]), Weight::new(100)),
            Err(AddKeyFailure::MaxKeysLimit)
        )
    }

    #[test]
    fn associated_keys_add_duplicate() {
        let pk = PublicKey([0u8; KEY_SIZE]);
        let weight = Weight::new(1);
        let mut keys = AssociatedKeys::new(pk, weight);
        assert_eq!(
            keys.add_key(pk, Weight::new(10)),
            Err(AddKeyFailure::DuplicateKey)
        );
        assert_eq!(keys.get(&pk), Some(&weight));
    }

    #[test]
    fn associated_keys_remove() {
        let pk = PublicKey([0u8; KEY_SIZE]);
        let weight = Weight::new(1);
        let mut keys = AssociatedKeys::new(pk, weight);
        assert!(keys.remove_key(&pk).is_ok());
        assert!(keys.remove_key(&PublicKey([1u8; KEY_SIZE])).is_err());
    }

    #[test]
    fn associated_keys_can_authorize_keys() {
        let key_1 = PublicKey::new([0; 32]);
        let key_2 = PublicKey::new([1; 32]);
        let key_3 = PublicKey::new([2; 32]);
        let mut keys = AssociatedKeys::empty();

        keys.add_key(key_2, Weight::new(2))
            .expect("should add key_1");
        keys.add_key(key_1, Weight::new(1))
            .expect("should add key_1");
        keys.add_key(key_3, Weight::new(3))
            .expect("should add key_1");

        let account = Account::new(
            [0u8; 32],
            0,
            BTreeMap::new(),
            PurseId::new(URef::new([0u8; 32], AccessRights::READ_ADD_WRITE)),
            keys,
            // deploy: 33 (3*11)
            ActionThresholds::new(Weight::new(33), Weight::new(48))
                .expect("should create thresholds"),
            AccountActivity::new(BlockTime(0), BlockTime(0)),
        );

        assert!(account.can_authorize(&BTreeSet::from_iter(vec![key_3, key_2, key_1])));
        assert!(account.can_authorize(&BTreeSet::from_iter(vec![key_1, key_3, key_2])));

        assert!(account.can_authorize(&BTreeSet::from_iter(vec![key_1, key_2])));
        assert!(account.can_authorize(&BTreeSet::from_iter(vec![key_1])));

        assert!(!account.can_authorize(&BTreeSet::from_iter(vec![
            key_1,
            key_2,
            PublicKey::new([42; 32])
        ])));
        assert!(!account.can_authorize(&BTreeSet::from_iter(vec![
            PublicKey::new([42; 32]),
            key_1,
            key_2
        ])));
        assert!(!account.can_authorize(&BTreeSet::from_iter(vec![
            PublicKey::new([43; 32]),
            PublicKey::new([44; 32]),
            PublicKey::new([42; 32])
        ])));
    }

    #[test]
    fn associated_keys_calculate_keys_once() {
        let key_1 = PublicKey::new([0; 32]);
        let key_2 = PublicKey::new([1; 32]);
        let key_3 = PublicKey::new([2; 32]);
        let mut keys = AssociatedKeys::empty();

        keys.add_key(key_2, Weight::new(2))
            .expect("should add key_1");
        keys.add_key(key_1, Weight::new(1))
            .expect("should add key_1");
        keys.add_key(key_3, Weight::new(3))
            .expect("should add key_1");

        assert_eq!(
            keys.calculate_keys_weight(&BTreeSet::from_iter(vec![
                key_1, key_2, key_3, key_1, key_2, key_3,
            ])),
            Weight::new(1 + 2 + 3)
        );
    }

    #[test]
    fn account_can_deploy_with() {
        let associated_keys = {
            let mut res = AssociatedKeys::new(PublicKey::new([1u8; 32]), Weight::new(1));
            res.add_key(PublicKey::new([2u8; 32]), Weight::new(11))
                .expect("should add key 1");
            res.add_key(PublicKey::new([3u8; 32]), Weight::new(11))
                .expect("should add key 2");
            res.add_key(PublicKey::new([4u8; 32]), Weight::new(11))
                .expect("should add key 3");
            res
        };
        let account = Account::new(
            [0u8; 32],
            0,
            BTreeMap::new(),
            PurseId::new(URef::new([0u8; 32], AccessRights::READ_ADD_WRITE)),
            associated_keys,
            // deploy: 33 (3*11)
            ActionThresholds::new(Weight::new(33), Weight::new(48))
                .expect("should create thresholds"),
            AccountActivity::new(BlockTime(0), BlockTime(0)),
        );

        // sum: 22, required 33 - can't deploy
        assert!(!account.can_deploy_with(&BTreeSet::from_iter(vec![
            PublicKey::new([3u8; 32]),
            PublicKey::new([2u8; 32]),
        ])));

        // sum: 33, required 33 - can deploy
        assert!(account.can_deploy_with(&BTreeSet::from_iter(vec![
            PublicKey::new([4u8; 32]),
            PublicKey::new([3u8; 32]),
            PublicKey::new([2u8; 32]),
        ])));

        // sum: 34, required 33 - can deploy
        assert!(account.can_deploy_with(&BTreeSet::from_iter(vec![
            PublicKey::new([2u8; 32]),
            PublicKey::new([1u8; 32]),
            PublicKey::new([4u8; 32]),
            PublicKey::new([3u8; 32]),
        ])));
    }

    #[test]
    fn associated_keys_total_weight() {
        let associated_keys = {
            let mut res = AssociatedKeys::new(PublicKey::new([1u8; 32]), Weight::new(1));
            res.add_key(PublicKey::new([2u8; 32]), Weight::new(11))
                .expect("should add key 1");
            res.add_key(PublicKey::new([3u8; 32]), Weight::new(12))
                .expect("should add key 2");
            res.add_key(PublicKey::new([4u8; 32]), Weight::new(13))
                .expect("should add key 3");
            res
        };
        assert_eq!(
            associated_keys.total_keys_weight(),
            Weight::new(1 + 11 + 12 + 13)
        );
    }

    #[test]
    fn account_can_manage_keys_with() {
        let associated_keys = {
            let mut res = AssociatedKeys::new(PublicKey::new([1u8; 32]), Weight::new(1));
            res.add_key(PublicKey::new([2u8; 32]), Weight::new(11))
                .expect("should add key 1");
            res.add_key(PublicKey::new([3u8; 32]), Weight::new(11))
                .expect("should add key 2");
            res.add_key(PublicKey::new([4u8; 32]), Weight::new(11))
                .expect("should add key 3");
            res
        };
        let account = Account::new(
            [0u8; 32],
            0,
            BTreeMap::new(),
            PurseId::new(URef::new([0u8; 32], AccessRights::READ_ADD_WRITE)),
            associated_keys,
            // deploy: 33 (3*11)
            ActionThresholds::new(Weight::new(11), Weight::new(33))
                .expect("should create thresholds"),
            AccountActivity::new(BlockTime(0), BlockTime(0)),
        );

        // sum: 22, required 33 - can't manage
        assert!(!account.can_manage_keys_with(&BTreeSet::from_iter(vec![
            PublicKey::new([3u8; 32]),
            PublicKey::new([2u8; 32]),
        ])));

        // sum: 33, required 33 - can manage
        assert!(account.can_manage_keys_with(&BTreeSet::from_iter(vec![
            PublicKey::new([4u8; 32]),
            PublicKey::new([3u8; 32]),
            PublicKey::new([2u8; 32]),
        ])));

        // sum: 34, required 33 - can manage
        assert!(account.can_manage_keys_with(&BTreeSet::from_iter(vec![
            PublicKey::new([2u8; 32]),
            PublicKey::new([1u8; 32]),
            PublicKey::new([4u8; 32]),
            PublicKey::new([3u8; 32]),
        ])));
    }

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

    fn should_create_new_action_thresholds() {
        let action_thresholds = ActionThresholds::new(Weight::new(1), Weight::new(42)).unwrap();
        assert_eq!(*action_thresholds.deployment(), Weight::new(1));
        assert_eq!(*action_thresholds.key_management(), Weight::new(42));
    }

    #[test]
    #[should_panic]
    fn should_not_create_action_thresholds_with_invalid_deployment_threshold() {
        // deployment cant be greater than key management
        ActionThresholds::new(Weight::new(5), Weight::new(1)).unwrap();
    }

    #[test]
    fn set_action_threshold_higher_than_total_weight() {
        let identity_key = PublicKey::new([1u8; 32]);
        let key_1 = PublicKey::new([2u8; 32]);
        let key_2 = PublicKey::new([3u8; 32]);
        let key_3 = PublicKey::new([4u8; 32]);
        let associated_keys = {
            let mut res = AssociatedKeys::new(identity_key, Weight::new(1));
            res.add_key(key_1, Weight::new(2))
                .expect("should add key 1");
            res.add_key(key_2, Weight::new(3))
                .expect("should add key 2");
            res.add_key(key_3, Weight::new(4))
                .expect("should add key 3");
            res
        };
        let mut account = Account::new(
            [0u8; 32],
            0,
            BTreeMap::new(),
            PurseId::new(URef::new([0u8; 32], AccessRights::READ_ADD_WRITE)),
            associated_keys,
            // deploy: 33 (3*11)
            ActionThresholds::new(Weight::new(33), Weight::new(48))
                .expect("should create thresholds"),
            AccountActivity::new(BlockTime(0), BlockTime(0)),
        );

        assert_eq!(
            account
                .set_action_threshold(ActionType::Deployment, Weight::new(1 + 2 + 3 + 4 + 1))
                .unwrap_err(),
            SetThresholdFailure::InsufficientTotalWeight,
        );
        assert_eq!(
            account
                .set_action_threshold(ActionType::Deployment, Weight::new(1 + 2 + 3 + 4 + 245))
                .unwrap_err(),
            SetThresholdFailure::InsufficientTotalWeight,
        )
    }

    #[test]
    fn remove_key_would_violate_action_thresholds() {
        let identity_key = PublicKey::new([1u8; 32]);
        let key_1 = PublicKey::new([2u8; 32]);
        let key_2 = PublicKey::new([3u8; 32]);
        let key_3 = PublicKey::new([4u8; 32]);
        let associated_keys = {
            let mut res = AssociatedKeys::new(identity_key, Weight::new(1));
            res.add_key(key_1, Weight::new(2))
                .expect("should add key 1");
            res.add_key(key_2, Weight::new(3))
                .expect("should add key 2");
            res.add_key(key_3, Weight::new(4))
                .expect("should add key 3");
            res
        };
        let mut account = Account::new(
            [0u8; 32],
            0,
            BTreeMap::new(),
            PurseId::new(URef::new([0u8; 32], AccessRights::READ_ADD_WRITE)),
            associated_keys,
            // deploy: 33 (3*11)
            ActionThresholds::new(Weight::new(1 + 2 + 3 + 4), Weight::new(1 + 2 + 3 + 4 + 5))
                .expect("should create thresholds"),
            AccountActivity::new(BlockTime(0), BlockTime(0)),
        );

        assert_eq!(
            account.remove_associated_key(key_3).unwrap_err(),
            RemoveKeyFailure::ThresholdViolation,
        )
    }

    #[test]
    fn updating_key_would_violate_action_thresholds() {
        let identity_key = PublicKey::new([1u8; 32]);
        let key_1 = PublicKey::new([2u8; 32]);
        let key_2 = PublicKey::new([3u8; 32]);
        let key_3 = PublicKey::new([4u8; 32]);
        let associated_keys = {
            let mut res = AssociatedKeys::new(identity_key, Weight::new(1));
            res.add_key(key_1, Weight::new(2))
                .expect("should add key 1");
            res.add_key(key_2, Weight::new(3))
                .expect("should add key 2");
            res.add_key(key_3, Weight::new(4))
                .expect("should add key 3");
            // 1 + 2 + 3 + 4
            res
        };
        let mut account = Account::new(
            [0u8; 32],
            0,
            BTreeMap::new(),
            PurseId::new(URef::new([0u8; 32], AccessRights::READ_ADD_WRITE)),
            associated_keys,
            // deploy: 33 (3*11)
            ActionThresholds::new(Weight::new(1 + 2 + 3 + 4), Weight::new(1 + 2 + 3 + 4 + 1))
                .expect("should create thresholds"),
            AccountActivity::new(BlockTime(0), BlockTime(0)),
        );

        // Decreases by 3
        assert_eq!(
            account
                .clone()
                .update_associated_key(key_3, Weight::new(1))
                .unwrap_err(),
            UpdateKeyFailure::ThresholdViolation,
        );

        // increase total weight (12)
        account
            .update_associated_key(identity_key, Weight::new(3))
            .unwrap();

        // variant a) decrease total weight by 1 (total 11)
        account
            .clone()
            .update_associated_key(key_3, Weight::new(3))
            .unwrap();
        // variant b) decrease total weight by 3 (total 9) - fail
        assert_eq!(
            account
                .clone()
                .update_associated_key(key_3, Weight::new(1))
                .unwrap_err(),
            UpdateKeyFailure::ThresholdViolation
        );
    }

    #[test]
    fn overflowing_keys_weight() {
        let associated_keys = {
            let mut res = AssociatedKeys::new(PublicKey::new([1u8; 32]), Weight::new(250));

            res.add_key(PublicKey::new([2u8; 32]), Weight::new(1))
                .expect("should add key 1");
            res.add_key(PublicKey::new([3u8; 32]), Weight::new(2))
                .expect("should add key 2");
            res.add_key(PublicKey::new([4u8; 32]), Weight::new(3))
                .expect("should add key 3");
            res
        };

        assert_eq!(
            associated_keys.calculate_keys_weight(&BTreeSet::from_iter(vec![
                PublicKey::new([1; 32]), // 250
                PublicKey::new([2; 32]), // 251
                PublicKey::new([3; 32]), // 253
                PublicKey::new([4; 32]), // 256 - error
            ])),
            Weight::new(255u8)
        );
    }
}
