use crate::bytesrepr::{Error, FromBytes, ToBytes, U32_SIZE, U64_SIZE, U8_SIZE};
use crate::key::{addr_to_hex, Key, UREF_SIZE};
use crate::uref::{AccessRights, URef, UREF_SIZE_SERIALIZED};
use alloc::collections::btree_map::BTreeMap;
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
    /// Required when adding/removing associated keys, changing threshold levels.
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

/// Represents an error that occurs during the change of a thresholds on an account.
///
/// It is represented by `i32` to be easily able to transform this value in an out
/// through FFI boundaries as a number.
///
/// The explicit numbering of the variants is done on purpose and whenever you plan to add
/// new variant, you should always extend it, and add a variant that does not exist already.
/// When adding new variants you should also remember to change
/// `From<i32> for SetThresholdFailure`.
///
/// This way we can ensure safety and backwards compatibility. Any changes should be carefully
/// reviewed and tested.
#[repr(i32)]
#[derive(Debug, Fail, PartialEq, Eq)]
pub enum SetThresholdFailure {
    #[fail(display = "New threshold should be lower or equal than deployment threshold")]
    KeyManagementThresholdError = 1,
    #[fail(display = "New threshold should be lower or equal than key management threshold")]
    DeploymentThresholdError = 2,
    #[fail(display = "Unable to set action threshold due to insufficient permissions")]
    PermissionDeniedError = 3,
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
            _ => Err(TryFromIntError(())),
        }
    }
}

impl ActionThresholds {
    /// Sets new threshold for [ActionType::Deployment].
    /// Should return an error if setting new threshold for `action_type` breaks one of the invariants.
    /// Currently, invariant is that `ActionType::Deployment` threshold shouldn't be higher than any other,
    /// which should be checked both when increasing `Deployment` threshold and decreasing the other.
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

#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd)]
pub struct BlockTime(pub u64);

/// Holds information about last usage time of specific action.
#[derive(Clone, Debug, PartialEq, Eq)]
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
    // `current_block_time` value is passed in from the node and is coming from the parent block.
    // [inactivity_period_limit] block time period after which account is eligible for recovery.
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

// TODO: This needs to be updated, `PUBLIC_KEY_SIZE` is not 32 bytes as KEY_SIZE * U8_SIZE.
// I am not changing that as I don't want to deal with ripple effect.

// Public key is encoded as its underlying [u8; 32] array, which in turn
// is serialized as u8 + [u8; 32], u8 represents the length and then 32 element array.
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
/// It is represented by `i32` to be easily able to transform this value in an out
/// through FFI boundaries as a number.
///
/// The explicit numbering of the variants is done on purpose and whenever you plan to add
/// new variant, you should always extend it, and add a variant that does not exist already.
/// When adding new variants you should also remember to change
/// `From<i32> for AddKeyFailure`.
///
/// This way we can ensure safety and backwards compatibility. Any changes should be carefully
/// reviewed and tested.
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
/// It is represented by `i32` to be easily able to transform this value in an out
/// through FFI boundaries as a number.
///
/// The explicit numbering of the variants is done on purpose and whenever you plan to add
/// new variant, you should always extend it, and add a variant that does not exist already.
/// When adding new variants you should also remember to change
/// `From<i32> for RemoveKeyFailure`.
///
/// This way we can ensure safety and backwards compatibility. Any changes should be carefully
/// reviewed and tested.
#[derive(Fail, Debug, Eq, PartialEq)]
#[repr(i32)]
pub enum RemoveKeyFailure {
    /// Key does not exist in the list of associated keys.
    #[fail(display = "Unable to remove a key that does not exist")]
    MissingKey = 1,
    #[fail(display = "Unable to remove associated key due to insufficient permissions")]
    PermissionDenied = 2,
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
            _ => Err(TryFromIntError(())),
        }
    }
}

/// Represents an error that happens when trying to update the value under a public key
/// associated with an account.
///
/// It is represented by `i32` to be easily able to transform this value in and out
/// through FFI boundaries as a number.
///
/// For backwards compatibility, the variants are explicitly ordered and will not be reordered;
/// variants added in future versions will be appended to extend the enum
/// and in the event that a variant is removed its ordinal will not be reused.
#[derive(PartialEq, Eq, Fail, Debug)]
#[repr(i32)]
pub enum UpdateKeyFailure {
    /// Key does not exist in the list of associated keys.
    #[fail(display = "Unable to update the value under an associated key that does not exist")]
    MissingKey = 1,
    #[fail(display = "Unable to add new associated key due to insufficient permissions")]
    PermissionDenied = 2,
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
            _ => Err(TryFromIntError(())),
        }
    }
}

#[derive(PartialOrd, Ord, PartialEq, Eq, Clone, Debug)]
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
    /// Returns true if value was found in the set prior to the removal, false otherwise.
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

    pub fn get_all(&self) -> &BTreeMap<PublicKey, Weight> {
        &self.0
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

    pub fn associated_keys(&self) -> &AssociatedKeys {
        &self.associated_keys
    }

    pub fn get_associated_keys(&self) -> &AssociatedKeys {
        &self.associated_keys
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
        // TODO(mpapierski): Authorized keys check EE-377
        self.associated_keys.add_key(public_key, weight)
    }

    pub fn remove_associated_key(&mut self, public_key: PublicKey) -> Result<(), RemoveKeyFailure> {
        // TODO(mpapierski): Authorized keys check EE-377
        self.associated_keys.remove_key(&public_key)
    }

    pub fn update_associated_key(
        &mut self,
        public_key: PublicKey,
        weight: Weight,
    ) -> Result<(), UpdateKeyFailure> {
        // TODO(mpapierski): Authorized keys check EE-377
        self.associated_keys.update_key(public_key, weight)
    }

    pub fn set_action_threshold(
        &mut self,
        action_type: ActionType,
        weight: Weight,
    ) -> Result<(), SetThresholdFailure> {
        // TODO(mpapierski): Authorized keys check EE-377
        self.action_thresholds.set_threshold(action_type, weight)
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
            // NOTE: we're ignoring potential errors (duplicate key, maximum number of elements).
            // This is safe, for now, as we were the ones that serialized `AssociatedKeys` in the
            // first place.
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
        Account, AccountActivity, AddKeyFailure, AssociatedKeys, BlockTime, PublicKey, PurseId,
        Weight, KEY_SIZE, MAX_KEYS,
    };
    use alloc::collections::btree_map::BTreeMap;
    use alloc::vec::Vec;
    use core::convert::TryFrom;

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
