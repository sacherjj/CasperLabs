#[cfg(test)]
mod tests;

use std::cell::RefCell;
use std::collections::{BTreeMap, BTreeSet, HashMap, HashSet};
use std::convert::{TryFrom, TryInto};
use std::fmt::Display;
use std::rc::Rc;

use blake2::digest::{Input, VariableOutput};
use blake2::VarBlake2b;
use rand::RngCore;
use rand_chacha::ChaChaRng;

use contract_ffi::bytesrepr::{deserialize, ToBytes};
use contract_ffi::execution::Phase;
use contract_ffi::key::{Key, LOCAL_SEED_SIZE};
use contract_ffi::uref::{AccessRights, URef};
use contract_ffi::value::account::{
    Account, ActionType, AddKeyFailure, BlockTime, PublicKey, RemoveKeyFailure,
    SetThresholdFailure, UpdateKeyFailure, Weight,
};
use contract_ffi::value::{Contract, Value};
use engine_shared::newtypes::{CorrelationId, Validated};
use engine_storage::global_state::StateReader;

use crate::engine_state::execution_effect::ExecutionEffect;
use crate::execution::Error;
use crate::tracking_copy::{AddResult, TrackingCopy};
use crate::URefAddr;

/// Holds information specific to the deployed contract.
pub struct RuntimeContext<'a, R> {
    state: Rc<RefCell<TrackingCopy<R>>>,
    // Enables look up of specific uref based on human-readable name
    uref_lookup: &'a mut BTreeMap<String, Key>,
    // Used to check uref is known before use (prevents forging urefs)
    known_urefs: HashMap<URefAddr, HashSet<AccessRights>>,
    // Original account for read only tasks taken before execution
    account: &'a Account,
    args: Vec<Vec<u8>>,
    authorization_keys: BTreeSet<PublicKey>,
    // Key pointing to the entity we are currently running
    //(could point at an account or contract in the global state)
    base_key: Key,
    blocktime: BlockTime,
    gas_limit: u64,
    gas_counter: u64,
    fn_store_id: u32,
    rng: Rc<RefCell<ChaChaRng>>,
    protocol_version: u64,
    correlation_id: CorrelationId,
    phase: Phase,
}

impl<'a, R: StateReader<Key, Value>> RuntimeContext<'a, R>
where
    R::Error: Into<Error>,
{
    #[allow(clippy::too_many_arguments)]
    pub fn new(
        state: Rc<RefCell<TrackingCopy<R>>>,
        uref_lookup: &'a mut BTreeMap<String, Key>,
        known_urefs: HashMap<URefAddr, HashSet<AccessRights>>,
        args: Vec<Vec<u8>>,
        authorization_keys: BTreeSet<PublicKey>,
        account: &'a Account,
        base_key: Key,
        blocktime: BlockTime,
        gas_limit: u64,
        gas_counter: u64,
        fn_store_id: u32,
        rng: Rc<RefCell<ChaChaRng>>,
        protocol_version: u64,
        correlation_id: CorrelationId,
        phase: Phase,
    ) -> Self {
        RuntimeContext {
            state,
            uref_lookup,
            known_urefs,
            args,
            account,
            authorization_keys,
            blocktime,
            base_key,
            gas_limit,
            gas_counter,
            fn_store_id,
            rng,
            protocol_version,
            correlation_id,
            phase,
        }
    }

    pub fn authorization_keys(&self) -> &BTreeSet<PublicKey> {
        &self.authorization_keys
    }

    pub fn get_uref(&self, name: &str) -> Option<&Key> {
        self.uref_lookup.get(name)
    }

    pub fn list_known_urefs(&self) -> &BTreeMap<String, Key> {
        &self.uref_lookup
    }

    pub fn fn_store_id(&self) -> u32 {
        self.fn_store_id
    }

    pub fn contains_uref(&self, name: &str) -> bool {
        self.uref_lookup.contains_key(name)
    }

    // Helper function to avoid duplication in `remove_uref`.
    fn remove_uref_from_contract(
        &mut self,
        contract_key: Key,
        mut contract: Contract,
        name: &str,
    ) -> Result<(), Error> {
        contract.get_urefs_lookup_mut().remove(name);
        // By this point in the code path, there is no further validation needed.
        let validated_uref = Validated::new(contract_key, Validated::valid)?;
        let validated_value = Validated::new(Value::Contract(contract), Validated::valid)?;

        self.state
            .borrow_mut()
            .write(validated_uref, validated_value);

        Ok(())
    }

    /// Remove URef from the `known_urefs` map of the current context.
    /// It removes both from the ephemeral map (RuntimeContext::known_urefs) but
    /// also persistable map (one that is found in the
    /// TrackingCopy/GlobalState).
    pub fn remove_uref(&mut self, name: &str) -> Result<(), Error> {
        match self.base_key() {
            public_key @ Key::Account(_) => {
                let mut account: Account = self.read_gs_typed(&public_key)?;
                self.uref_lookup.remove(name);
                account.get_urefs_lookup_mut().remove(name);
                let validated_uref = Validated::new(public_key, Validated::valid)?;
                let validated_value =
                    Validated::new(Value::Account(account), |value| self.validate_keys(value))?;

                self.state
                    .borrow_mut()
                    .write(validated_uref, validated_value);

                Ok(())
            }
            contract_uref @ Key::URef(_) => {
                // We do not need to validate the base key because a contract
                // is always able to remove keys from its own known_urefs.
                let contract_key = Validated::new(contract_uref, Validated::valid)?;

                let contract: Contract = {
                    let value: Value = self
                        .state
                        .borrow_mut()
                        .read(self.correlation_id, &contract_key)
                        .map_err(Into::into)?
                        .ok_or_else(|| Error::KeyNotFound(contract_uref))?;

                    value.try_into().map_err(|found| {
                        Error::TypeMismatch(engine_shared::transform::TypeMismatch {
                            expected: "Contract".to_owned(),
                            found,
                        })
                    })?
                };

                self.uref_lookup.remove(name);
                self.remove_uref_from_contract(contract_uref, contract, name)
            }
            contract_hash @ Key::Hash(_) => {
                let contract: Contract = self.read_gs_typed(&contract_hash)?;
                self.uref_lookup.remove(name);
                self.remove_uref_from_contract(contract_hash, contract, name)
            }
            contract_local @ Key::Local(_) => {
                let contract: Contract = self.read_gs_typed(&contract_local)?;
                self.uref_lookup.remove(name);
                self.remove_uref_from_contract(contract_local, contract, name)
            }
        }
    }

    pub fn get_caller(&self) -> PublicKey {
        self.account.pub_key().into()
    }

    pub fn get_blocktime(&self) -> BlockTime {
        self.blocktime
    }

    pub fn add_urefs(&mut self, urefs_map: HashMap<URefAddr, HashSet<AccessRights>>) {
        self.known_urefs.extend(urefs_map);
    }

    pub fn account(&self) -> &'a Account {
        &self.account
    }

    pub fn args(&self) -> &Vec<Vec<u8>> {
        &self.args
    }

    pub fn rng(&self) -> Rc<RefCell<ChaChaRng>> {
        Rc::clone(&self.rng)
    }

    pub fn state(&self) -> Rc<RefCell<TrackingCopy<R>>> {
        Rc::clone(&self.state)
    }

    pub fn gas_limit(&self) -> u64 {
        self.gas_limit
    }

    pub fn gas_counter(&self) -> u64 {
        self.gas_counter
    }

    pub fn set_gas_counter(&mut self, new_gas_counter: u64) {
        self.gas_counter = new_gas_counter;
    }

    pub fn inc_fn_store_id(&mut self) {
        self.fn_store_id += 1;
    }

    pub fn base_key(&self) -> Key {
        self.base_key
    }

    pub fn seed(&self) -> [u8; LOCAL_SEED_SIZE] {
        match self.base_key {
            Key::Account(bytes) => bytes,
            Key::Hash(bytes) => bytes,
            Key::URef(uref) => uref.addr(),
            Key::Local(hash) => hash,
        }
    }

    pub fn protocol_version(&self) -> u64 {
        self.protocol_version
    }

    pub fn correlation_id(&self) -> CorrelationId {
        self.correlation_id
    }

    pub fn phase(&self) -> Phase {
        self.phase
    }

    /// Generates new function address.
    /// Function address is deterministic. It is a hash of public key, nonce and
    /// `fn_store_id`, which is a counter that is being incremented after
    /// every function generation. If function address was based only on
    /// account's public key and deploy's nonce, then all function addresses
    /// generated within one deploy would have been the same.
    pub fn new_function_address(&mut self) -> Result<[u8; 32], Error> {
        let mut pre_hash_bytes = Vec::with_capacity(44); //32 byte pk + 8 byte nonce + 4 byte ID
        pre_hash_bytes.extend_from_slice(&self.account().pub_key());
        pre_hash_bytes.append(&mut self.account().nonce().to_bytes()?);
        pre_hash_bytes.append(&mut self.fn_store_id().to_bytes()?);

        self.inc_fn_store_id();

        let mut hasher = VarBlake2b::new(32).unwrap();
        hasher.input(&pre_hash_bytes);
        let mut hash_bytes = [0; 32];
        hasher.variable_result(|hash| hash_bytes.clone_from_slice(hash));
        Ok(hash_bytes)
    }

    pub fn new_uref(&mut self, value: Value) -> Result<Key, Error> {
        let uref = {
            let mut addr = [0u8; 32];
            self.rng.borrow_mut().fill_bytes(&mut addr);
            URef::new(addr, AccessRights::READ_ADD_WRITE)
        };
        let key = Key::URef(uref);
        self.insert_uref(uref);
        self.write_gs(key, value)?;
        Ok(key)
    }

    /// Adds `key` to the map of named keys of current context.
    pub fn add_uref(&mut self, name: String, key: Key) -> Result<(), Error> {
        // No need to perform actual validation on the base key because an account or
        // contract (i.e. the element stored under `base_key`) is allowed to add
        // new named keys to itself.
        let base_key = Validated::new(self.base_key(), Validated::valid)?;

        let validated_value = Validated::new(Value::NamedKey(name.clone(), key), |v| {
            self.validate_keys(&v)
        })?;
        self.add_gs_validated(base_key, validated_value)?;

        // key was already validated successfully as part of validated_value above
        let validated_key = Validated::new(key, Validated::valid)?;
        self.insert_named_uref(name, validated_key);
        Ok(())
    }

    pub fn read_ls(&mut self, key: &[u8]) -> Result<Option<Value>, Error> {
        let seed = self.seed();
        self.read_ls_with_seed(seed, key)
    }

    /// DO NOT EXPOSE THIS VIA THE FFI
    pub fn read_ls_with_seed(
        &mut self,
        seed: [u8; LOCAL_SEED_SIZE],
        key_bytes: &[u8],
    ) -> Result<Option<Value>, Error> {
        let key = Key::local(seed, key_bytes);
        let validated_key = Validated::new(key, Validated::valid)?;
        self.state
            .borrow_mut()
            .read(self.correlation_id, &validated_key)
            .map_err(Into::into)
    }

    pub fn write_ls(&mut self, key_bytes: &[u8], value: Value) -> Result<(), Error> {
        let seed = self.seed();
        let key = Key::local(seed, key_bytes);
        let validated_key = Validated::new(key, Validated::valid)?;
        let validated_value = Validated::new(value, Validated::valid)?;
        self.state
            .borrow_mut()
            .write(validated_key, validated_value);
        Ok(())
    }

    pub fn read_gs(&mut self, key: &Key) -> Result<Option<Value>, Error> {
        let validated_key = Validated::new(*key, |key| {
            self.validate_readable(&key).and(self.validate_key(&key))
        })?;
        self.state
            .borrow_mut()
            .read(self.correlation_id, &validated_key)
            .map_err(Into::into)
    }

    /// DO NOT EXPOSE THIS VIA THE FFI
    pub fn read_gs_direct(&mut self, key: &Key) -> Result<Option<Value>, Error> {
        let validated_key = Validated::new(*key, Validated::valid)?;
        self.state
            .borrow_mut()
            .read(self.correlation_id, &validated_key)
            .map_err(Into::into)
    }

    /// This method is a wrapper over `read_gs` in the sense
    /// that it extracts the type held by a Value stored in the
    /// global state in a type safe manner.
    ///
    /// This is useful if you want to get the exact type
    /// from global state.
    pub fn read_gs_typed<T>(&mut self, key: &Key) -> Result<T, Error>
    where
        T: TryFrom<Value>,
        T::Error: Display,
    {
        let value = match self.read_gs(&key)? {
            None => return Err(Error::KeyNotFound(*key)),
            Some(value) => value,
        };

        value.try_into().map_err(|s| {
            Error::FunctionNotFound(format!("Value at {:?} has invalid type: {}", key, s))
        })
    }

    pub fn write_gs(&mut self, key: Key, value: Value) -> Result<(), Error> {
        let validated_key: Validated<Key> = Validated::new(key, |key| {
            self.validate_writeable(&key).and(self.validate_key(&key))
        })?;
        let validated_value = Validated::new(value, |value| self.validate_keys(&value))?;
        self.state
            .borrow_mut()
            .write(validated_key, validated_value);
        Ok(())
    }

    pub fn read_account(&mut self, key: &Key) -> Result<Option<Value>, Error> {
        if let Key::Account(_) = key {
            let validated_key = Validated::new(*key, |key| self.validate_key(&key))?;
            self.state
                .borrow_mut()
                .read(self.correlation_id, &validated_key)
                .map_err(Into::into)
        } else {
            panic!("Do not use this function for reading from non-account keys")
        }
    }

    pub fn write_account(&mut self, key: Key, account: Account) -> Result<(), Error> {
        if let Key::Account(_) = key {
            let validated_key = Validated::new(key, |key| self.validate_key(&key))?;
            let validated_value =
                Validated::new(Value::Account(account), |value| self.validate_keys(&value))?;
            self.state
                .borrow_mut()
                .write(validated_key, validated_value);
            Ok(())
        } else {
            panic!("Do not use this function for writing non-account keys")
        }
    }

    pub fn store_contract(&mut self, contract: Value) -> Result<[u8; 32], Error> {
        let new_hash = self.new_function_address()?;
        let validated_value = Validated::new(contract, |cntr| self.validate_keys(&cntr))?;
        let validated_key = Validated::new(Key::Hash(new_hash), Validated::valid)?;
        self.state
            .borrow_mut()
            .write(validated_key, validated_value);
        Ok(new_hash)
    }

    pub fn insert_named_uref(&mut self, name: String, key: Validated<Key>) {
        if let Key::URef(uref) = *key {
            self.insert_uref(uref);
        }
        self.uref_lookup.insert(name, *key);
    }

    pub fn insert_uref(&mut self, uref: URef) {
        if let Some(rights) = uref.access_rights() {
            let entry_rights = self
                .known_urefs
                .entry(uref.addr())
                .or_insert_with(|| std::iter::empty().collect());
            entry_rights.insert(rights);
        }
    }

    pub fn effect(&self) -> ExecutionEffect {
        self.state.borrow_mut().effect()
    }

    /// Validates whether keys used in the `value` are not forged.
    pub fn validate_keys(&self, value: &Value) -> Result<(), Error> {
        match value {
            Value::Int32(_)
            | Value::UInt128(_)
            | Value::UInt256(_)
            | Value::UInt512(_)
            | Value::ByteArray(_)
            | Value::ListInt32(_)
            | Value::String(_)
            | Value::ListString(_)
            | Value::Unit
            | Value::UInt64(_) => Ok(()),
            Value::NamedKey(_, key) => self.validate_key(&key),
            Value::Key(key) => self.validate_key(&key),
            Value::Account(account) => {
                // This should never happen as accounts can't be created by contracts.
                // I am putting this here for the sake of completness.
                account
                    .urefs_lookup()
                    .values()
                    .try_for_each(|key| self.validate_key(key))
            }
            Value::Contract(contract) => contract
                .urefs_lookup()
                .values()
                .try_for_each(|key| self.validate_key(key)),
        }
    }

    /// Validates whether key is not forged (whether it can be found in the
    /// `known_urefs`) and whether the version of a key that contract wants
    /// to use, has access rights that are less powerful than access rights'
    /// of the key in the `known_urefs`.
    pub fn validate_key(&self, key: &Key) -> Result<(), Error> {
        let uref = match key {
            Key::URef(uref) => uref,
            _ => return Ok(()),
        };
        self.validate_uref(uref)
    }

    pub fn validate_uref(&self, uref: &URef) -> Result<(), Error> {
        if self.account.purse_id().value().addr() == uref.addr() {
            // If passed uref matches account's purse then we have to also validate their
            // access rights.
            if let Some(rights) = self.account.purse_id().value().access_rights() {
                if let Some(uref_rights) = uref.access_rights() {
                    // Access rights of the passed uref, and the account's purse_id should match
                    if rights & uref_rights == uref_rights {
                        return Ok(());
                    }
                }
            }
        }

        // Check if the `key` is known
        if let Some(known_rights) = self.known_urefs.get(&uref.addr()) {
            if let Some(new_rights) = uref.access_rights() {
                // check if we have sufficient access rights
                if known_rights
                    .iter()
                    .any(|right| *right & new_rights == new_rights)
                {
                    Ok(())
                } else {
                    Err(Error::ForgedReference(*uref))
                }
            } else {
                Ok(()) // uref is known and no additional rights are needed
            }
        } else {
            // uref is not known
            Err(Error::ForgedReference(*uref))
        }
    }

    pub fn deserialize_keys(&self, bytes: &[u8]) -> Result<Vec<Key>, Error> {
        let keys: Vec<Key> = deserialize(bytes)?;
        keys.iter().try_for_each(|k| self.validate_key(k))?;
        Ok(keys)
    }

    pub fn deserialize_urefs(&self, bytes: &[u8]) -> Result<Vec<URef>, Error> {
        let keys: Vec<URef> = deserialize(bytes)?;
        keys.iter().try_for_each(|k| self.validate_uref(k))?;
        Ok(keys)
    }

    fn validate_readable(&self, key: &Key) -> Result<(), Error> {
        if self.is_readable(&key) {
            Ok(())
        } else {
            Err(Error::InvalidAccess {
                required: AccessRights::READ,
            })
        }
    }

    fn validate_addable(&self, key: &Key) -> Result<(), Error> {
        if self.is_addable(&key) {
            Ok(())
        } else {
            Err(Error::InvalidAccess {
                required: AccessRights::ADD,
            })
        }
    }

    fn validate_writeable(&self, key: &Key) -> Result<(), Error> {
        if self.is_writeable(&key) {
            Ok(())
        } else {
            Err(Error::InvalidAccess {
                required: AccessRights::WRITE,
            })
        }
    }

    // Tests whether reading from the `key` is valid.
    pub fn is_readable(&self, key: &Key) -> bool {
        match key {
            Key::Account(_) => &self.base_key() == key,
            Key::Hash(_) => true,
            Key::URef(uref) => uref.is_readable(),
            Key::Local(_) => false,
        }
    }

    /// Tests whether addition to `key` is valid.
    pub fn is_addable(&self, key: &Key) -> bool {
        match key {
            Key::Account(_) | Key::Hash(_) => &self.base_key() == key,
            Key::URef(uref) => uref.is_addable(),
            Key::Local(_) => false,
        }
    }

    // Test whether writing to `key` is valid.
    pub fn is_writeable(&self, key: &Key) -> bool {
        match key {
            Key::Account(_) | Key::Hash(_) => false,
            Key::URef(uref) => uref.is_writeable(),
            Key::Local(_) => false,
        }
    }

    /// Adds `value` to the `key`. The premise for being able to `add` value is
    /// that the type of it [value] can be added (is a Monoid). If the
    /// values can't be added, either because they're not a Monoid or if the
    /// value stored under `key` has different type, then `TypeMismatch`
    /// errors is returned.
    pub fn add_gs(&mut self, key: Key, value: Value) -> Result<(), Error> {
        let validated_key = Validated::new(key, |k| {
            self.validate_addable(&k).and(self.validate_key(&k))
        })?;
        let validated_value = Validated::new(value, |v| self.validate_keys(&v))?;
        self.add_gs_validated(validated_key, validated_value)
    }

    fn add_gs_validated(
        &mut self,
        validated_key: Validated<Key>,
        validated_value: Validated<Value>,
    ) -> Result<(), Error> {
        match self
            .state
            .borrow_mut()
            .add(self.correlation_id, validated_key, validated_value)
        {
            Err(storage_error) => Err(storage_error.into()),
            Ok(AddResult::Success) => Ok(()),
            Ok(AddResult::KeyNotFound(key)) => Err(Error::KeyNotFound(key)),
            Ok(AddResult::TypeMismatch(type_mismatch)) => Err(Error::TypeMismatch(type_mismatch)),
        }
    }

    pub fn add_associated_key(
        &mut self,
        public_key: PublicKey,
        weight: Weight,
    ) -> Result<(), Error> {
        // Check permission to modify associated keys
        if self.base_key() != Key::Account(self.account().pub_key()) {
            // Exit early with error to avoid mutations
            return Err(AddKeyFailure::PermissionDenied.into());
        }

        if !self
            .account()
            .can_manage_keys_with(&self.authorization_keys)
        {
            // Exit early if authorization keys weight doesn't exceed required
            // key management threshold
            return Err(AddKeyFailure::PermissionDenied.into());
        }

        // Converts an account's public key into a URef
        let key = Key::Account(self.account().pub_key());

        // Take an account out of the global state
        let mut account: Account = self.read_gs_typed(&key)?;

        // Exit early in case of error without updating global state
        account
            .add_associated_key(public_key, weight)
            .map_err(Error::from)?;

        let validated_uref = Validated::new(key, Validated::valid)?;
        let validated_value =
            Validated::new(Value::Account(account), |value| self.validate_keys(value))?;

        self.state
            .borrow_mut()
            .write(validated_uref, validated_value);

        Ok(())
    }

    pub fn remove_associated_key(&mut self, public_key: PublicKey) -> Result<(), Error> {
        // Check permission to modify associated keys
        if self.base_key() != Key::Account(self.account().pub_key()) {
            // Exit early with error to avoid mutations
            return Err(RemoveKeyFailure::PermissionDenied.into());
        }

        if !self
            .account()
            .can_manage_keys_with(&self.authorization_keys)
        {
            // Exit early if authorization keys weight doesn't exceed required
            // key management threshold
            return Err(RemoveKeyFailure::PermissionDenied.into());
        }

        // Converts an account's public key into a URef
        let key = Key::Account(self.account().pub_key());

        // Take an account out of the global state
        let mut account: Account = self.read_gs_typed(&key)?;

        // Exit early in case of error without updating global state
        account
            .remove_associated_key(public_key)
            .map_err(Error::from)?;

        let validated_uref = Validated::new(key, Validated::valid)?;
        let validated_value =
            Validated::new(Value::Account(account), |value| self.validate_keys(value))?;

        self.state
            .borrow_mut()
            .write(validated_uref, validated_value);

        Ok(())
    }

    pub fn update_associated_key(
        &mut self,
        public_key: PublicKey,
        weight: Weight,
    ) -> Result<(), Error> {
        // Check permission to modify associated keys
        if self.base_key() != Key::Account(self.account().pub_key()) {
            // Exit early with error to avoid mutations
            return Err(UpdateKeyFailure::PermissionDenied.into());
        }

        if !self
            .account()
            .can_manage_keys_with(&self.authorization_keys)
        {
            // Exit early if authorization keys weight doesn't exceed required
            // key management threshold
            return Err(UpdateKeyFailure::PermissionDenied.into());
        }

        // Converts an account's public key into a URef
        let key = Key::Account(self.account().pub_key());

        // Take an account out of the global state
        let mut account: Account = self.read_gs_typed(&key)?;

        // Exit early in case of error without updating global state
        account
            .update_associated_key(public_key, weight)
            .map_err(Error::from)?;

        let validated_uref = Validated::new(key, Validated::valid)?;
        let validated_value =
            Validated::new(Value::Account(account), |value| self.validate_keys(value))?;

        self.state
            .borrow_mut()
            .write(validated_uref, validated_value);

        Ok(())
    }

    pub fn set_action_threshold(
        &mut self,
        action_type: ActionType,
        threshold: Weight,
    ) -> Result<(), Error> {
        // Check permission to modify associated keys
        if self.base_key() != Key::Account(self.account().pub_key()) {
            // Exit early with error to avoid mutations
            return Err(SetThresholdFailure::PermissionDeniedError.into());
        }

        if !self
            .account()
            .can_manage_keys_with(&self.authorization_keys)
        {
            // Exit early if authorization keys weight doesn't exceed required
            // key management threshold
            return Err(SetThresholdFailure::PermissionDeniedError.into());
        }

        // Converts an account's public key into a URef
        let key = Key::Account(self.account().pub_key());

        // Take an account out of the global state
        let mut account: Account = self.read_gs_typed(&key)?;

        // Exit early in case of error without updating global state
        account
            .set_action_threshold(action_type, threshold)
            .map_err(Error::from)?;

        let validated_uref = Validated::new(key, Validated::valid)?;
        let validated_value =
            Validated::new(Value::Account(account), |value| self.validate_keys(value))?;

        self.state
            .borrow_mut()
            .write(validated_uref, validated_value);

        Ok(())
    }
}
