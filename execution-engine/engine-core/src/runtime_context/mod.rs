use std::{
    cell::RefCell,
    collections::{BTreeMap, BTreeSet, HashMap, HashSet},
    convert::{TryFrom, TryInto},
    fmt::Debug,
    rc::Rc,
};

use blake2::{
    digest::{Input, VariableOutput},
    VarBlake2b,
};

use engine_shared::{
    account::Account, contract::Contract, gas::Gas, newtypes::CorrelationId,
    stored_value::StoredValue,
};
use engine_storage::{global_state::StateReader, protocol_data::ProtocolData};
use types::{
    account::{
        ActionType, AddKeyFailure, PublicKey, PurseId, RemoveKeyFailure, SetThresholdFailure,
        UpdateKeyFailure, Weight,
    },
    bytesrepr::{self, ToBytes},
    AccessRights, BlockTime, CLType, CLValue, Key, Phase, ProtocolVersion, URef, LOCAL_SEED_LENGTH,
};

use crate::{
    engine_state::{execution_effect::ExecutionEffect, SYSTEM_ACCOUNT_ADDR},
    execution::{AddressGenerator, Error},
    tracking_copy::{AddResult, TrackingCopy},
    Address,
};

#[cfg(test)]
mod tests;

/// Attenuates given URef for a given account context.
///
/// System account transfers given URefs into READ_ADD_WRITE access rights,
/// and any other URef is transformed into READ only URef.
pub(crate) fn attenuate_uref_for_account(account: &Account, uref: URef) -> URef {
    if account.pub_key() == SYSTEM_ACCOUNT_ADDR {
        // If the system account calls this function, it is given READ_ADD_WRITE access.
        uref.into_read_add_write()
    } else {
        // If a user calls this function, they are given READ access.
        uref.into_read()
    }
}

/// Holds information specific to the deployed contract.
pub struct RuntimeContext<'a, R> {
    state: Rc<RefCell<TrackingCopy<R>>>,
    // Enables look up of specific uref based on human-readable name
    named_keys: &'a mut BTreeMap<String, Key>,
    // Used to check uref is known before use (prevents forging urefs)
    access_rights: HashMap<Address, HashSet<AccessRights>>,
    // Original account for read only tasks taken before execution
    account: &'a Account,
    args: Vec<CLValue>,
    authorization_keys: BTreeSet<PublicKey>,
    // Key pointing to the entity we are currently running
    //(could point at an account or contract in the global state)
    base_key: Key,
    blocktime: BlockTime,
    deploy_hash: [u8; 32],
    gas_limit: Gas,
    gas_counter: Gas,
    fn_store_id: u32,
    address_generator: Rc<RefCell<AddressGenerator>>,
    protocol_version: ProtocolVersion,
    correlation_id: CorrelationId,
    phase: Phase,
    protocol_data: ProtocolData,
}

impl<'a, R> RuntimeContext<'a, R>
where
    R: StateReader<Key, StoredValue>,
    R::Error: Into<Error>,
{
    #[allow(clippy::too_many_arguments)]
    pub fn new(
        state: Rc<RefCell<TrackingCopy<R>>>,
        named_keys: &'a mut BTreeMap<String, Key>,
        access_rights: HashMap<Address, HashSet<AccessRights>>,
        args: Vec<CLValue>,
        authorization_keys: BTreeSet<PublicKey>,
        account: &'a Account,
        base_key: Key,
        blocktime: BlockTime,
        deploy_hash: [u8; 32],
        gas_limit: Gas,
        gas_counter: Gas,
        fn_store_id: u32,
        address_generator: Rc<RefCell<AddressGenerator>>,
        protocol_version: ProtocolVersion,
        correlation_id: CorrelationId,
        phase: Phase,
        protocol_data: ProtocolData,
    ) -> Self {
        RuntimeContext {
            state,
            named_keys,
            access_rights,
            args,
            account,
            authorization_keys,
            blocktime,
            deploy_hash,
            base_key,
            gas_limit,
            gas_counter,
            fn_store_id,
            address_generator,
            protocol_version,
            correlation_id,
            phase,
            protocol_data,
        }
    }

    pub fn authorization_keys(&self) -> &BTreeSet<PublicKey> {
        &self.authorization_keys
    }

    pub fn named_keys_get(&self, name: &str) -> Option<&Key> {
        self.named_keys.get(name)
    }

    pub fn named_keys(&self) -> &BTreeMap<String, Key> {
        &self.named_keys
    }

    pub fn fn_store_id(&self) -> u32 {
        self.fn_store_id
    }

    pub fn named_keys_contains_key(&self, name: &str) -> bool {
        self.named_keys.contains_key(name)
    }

    // Helper function to avoid duplication in `remove_uref`.
    fn remove_key_from_contract(
        &mut self,
        key: Key,
        mut contract: Contract,
        name: &str,
    ) -> Result<(), Error> {
        contract.named_keys_mut().remove(name);

        let contract_value = StoredValue::Contract(contract);

        self.state.borrow_mut().write(key, contract_value);

        Ok(())
    }

    /// Remove Key from the `named_keys` map of the current context.
    /// It removes both from the ephemeral map (RuntimeContext::named_keys) but
    /// also persistable map (one that is found in the
    /// TrackingCopy/GlobalState).
    pub fn remove_key(&mut self, name: &str) -> Result<(), Error> {
        match self.base_key() {
            public_key @ Key::Account(_) => {
                let account: Account = {
                    let mut account: Account = self.read_gs_typed(&public_key)?;
                    account.named_keys_mut().remove(name);
                    account
                };
                self.named_keys.remove(name);
                let account_value = self.account_to_validated_value(account)?;
                self.state.borrow_mut().write(public_key, account_value);
                Ok(())
            }
            contract_uref @ Key::URef(_) => {
                let contract: Contract = {
                    let value: StoredValue = self
                        .state
                        .borrow_mut()
                        .read(self.correlation_id, &contract_uref)
                        .map_err(Into::into)?
                        .ok_or_else(|| Error::KeyNotFound(contract_uref))?;

                    value.try_into().map_err(Error::TypeMismatch)?
                };

                self.named_keys.remove(name);
                self.remove_key_from_contract(contract_uref, contract, name)
            }
            contract_hash @ Key::Hash(_) => {
                let contract: Contract = self.read_gs_typed(&contract_hash)?;
                self.named_keys.remove(name);
                self.remove_key_from_contract(contract_hash, contract, name)
            }
            contract_local @ Key::Local(_) => {
                let contract: Contract = self.read_gs_typed(&contract_local)?;
                self.named_keys.remove(name);
                self.remove_key_from_contract(contract_local, contract, name)
            }
        }
    }

    pub fn get_caller(&self) -> PublicKey {
        self.account.pub_key().into()
    }

    pub fn get_blocktime(&self) -> BlockTime {
        self.blocktime
    }

    pub fn get_deployhash(&self) -> [u8; 32] {
        self.deploy_hash
    }

    pub fn access_rights_extend(&mut self, access_rights: HashMap<Address, HashSet<AccessRights>>) {
        self.access_rights.extend(access_rights);
    }

    pub fn account(&self) -> &'a Account {
        &self.account
    }

    pub fn args(&self) -> &Vec<CLValue> {
        &self.args
    }

    pub fn address_generator(&self) -> Rc<RefCell<AddressGenerator>> {
        Rc::clone(&self.address_generator)
    }

    pub fn state(&self) -> Rc<RefCell<TrackingCopy<R>>> {
        Rc::clone(&self.state)
    }

    pub fn gas_limit(&self) -> Gas {
        self.gas_limit
    }

    pub fn gas_counter(&self) -> Gas {
        self.gas_counter
    }

    pub fn set_gas_counter(&mut self, new_gas_counter: Gas) {
        self.gas_counter = new_gas_counter;
    }

    pub fn inc_fn_store_id(&mut self) {
        self.fn_store_id += 1;
    }

    pub fn base_key(&self) -> Key {
        self.base_key
    }

    pub fn seed(&self) -> [u8; LOCAL_SEED_LENGTH] {
        match self.base_key {
            Key::Account(bytes) => bytes,
            Key::Hash(bytes) => bytes,
            Key::URef(uref) => uref.addr(),
            Key::Local(hash) => hash,
        }
    }

    pub fn protocol_version(&self) -> ProtocolVersion {
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
        let mut pre_hash_bytes = Vec::with_capacity(36); //32 bytes for deploy hash + 4 bytes ID
        pre_hash_bytes.extend_from_slice(&self.deploy_hash);
        pre_hash_bytes.append(&mut self.fn_store_id().into_bytes()?);

        self.inc_fn_store_id();

        let mut hasher = VarBlake2b::new(32).unwrap();
        hasher.input(&pre_hash_bytes);
        let mut hash_bytes = [0; 32];
        hasher.variable_result(|hash| hash_bytes.clone_from_slice(hash));
        Ok(hash_bytes)
    }

    pub fn new_uref(&mut self, value: StoredValue) -> Result<Key, Error> {
        let uref = {
            let addr = self.address_generator.borrow_mut().create_address();
            URef::new(addr, AccessRights::READ_ADD_WRITE)
        };
        let key = Key::URef(uref);
        self.insert_uref(uref);
        self.write_gs(key, value)?;
        Ok(key)
    }

    /// Puts `key` to the map of named keys of current context.
    pub fn put_key(&mut self, name: String, key: Key) -> Result<(), Error> {
        // No need to perform actual validation on the base key because an account or contract (i.e.
        // the element stored under `base_key`) is allowed to add new named keys to itself.
        let named_key_value = StoredValue::CLValue(CLValue::from_t((name.clone(), key))?);
        self.validate_value(&named_key_value)?;

        self.add_unsafe(self.base_key(), named_key_value)?;
        self.insert_key(name, key);
        Ok(())
    }

    pub fn read_ls(&mut self, key: &[u8]) -> Result<Option<CLValue>, Error> {
        let seed = self.seed();
        self.read_ls_with_seed(seed, key)
    }

    /// DO NOT EXPOSE THIS VIA THE FFI
    pub fn read_ls_with_seed(
        &mut self,
        seed: [u8; LOCAL_SEED_LENGTH],
        key_bytes: &[u8],
    ) -> Result<Option<CLValue>, Error> {
        let key = Key::local(seed, key_bytes);
        let maybe_stored_value = self
            .state
            .borrow_mut()
            .read(self.correlation_id, &key)
            .map_err(Into::into)?;

        if let Some(stored_value) = maybe_stored_value {
            Ok(Some(stored_value.try_into().map_err(Error::TypeMismatch)?))
        } else {
            Ok(None)
        }
    }

    pub fn write_ls(&mut self, key_bytes: &[u8], cl_value: CLValue) -> Result<(), Error> {
        let seed = self.seed();
        let key = Key::local(seed, key_bytes);
        self.state
            .borrow_mut()
            .write(key, StoredValue::CLValue(cl_value));
        Ok(())
    }

    pub fn read_gs(&mut self, key: &Key) -> Result<Option<StoredValue>, Error> {
        self.validate_readable(key)?;
        self.validate_key(key)?;

        self.state
            .borrow_mut()
            .read(self.correlation_id, key)
            .map_err(Into::into)
    }

    /// DO NOT EXPOSE THIS VIA THE FFI
    pub fn read_gs_direct(&mut self, key: &Key) -> Result<Option<StoredValue>, Error> {
        self.state
            .borrow_mut()
            .read(self.correlation_id, key)
            .map_err(Into::into)
    }

    /// This method is a wrapper over `read_gs` in the sense that it extracts the type held by a
    /// `StoredValue` stored in the global state in a type safe manner.
    ///
    /// This is useful if you want to get the exact type from global state.
    pub fn read_gs_typed<T>(&mut self, key: &Key) -> Result<T, Error>
    where
        T: TryFrom<StoredValue>,
        T::Error: Debug,
    {
        let value = match self.read_gs(&key)? {
            None => return Err(Error::KeyNotFound(*key)),
            Some(value) => value,
        };

        value.try_into().map_err(|error| {
            Error::FunctionNotFound(format!(
                "Type mismatch for value under {:?}: {:?}",
                key, error
            ))
        })
    }

    pub fn write_gs(&mut self, key: Key, value: StoredValue) -> Result<(), Error> {
        self.validate_writeable(&key)?;
        self.validate_key(&key)?;
        self.validate_value(&value)?;
        self.state.borrow_mut().write(key, value);
        Ok(())
    }

    pub fn read_account(&mut self, key: &Key) -> Result<Option<StoredValue>, Error> {
        if let Key::Account(_) = key {
            self.validate_key(key)?;
            self.state
                .borrow_mut()
                .read(self.correlation_id, key)
                .map_err(Into::into)
        } else {
            panic!("Do not use this function for reading from non-account keys")
        }
    }

    pub fn write_account(&mut self, key: Key, account: Account) -> Result<(), Error> {
        if let Key::Account(_) = key {
            self.validate_key(&key)?;
            let account_value = self.account_to_validated_value(account)?;
            self.state.borrow_mut().write(key, account_value);
            Ok(())
        } else {
            panic!("Do not use this function for writing non-account keys")
        }
    }

    pub fn store_function(&mut self, contract: StoredValue) -> Result<[u8; 32], Error> {
        self.validate_value(&contract)?;
        if let Key::URef(contract_ref) = self.new_uref(contract)? {
            Ok(contract_ref.addr())
        } else {
            // TODO: make new_uref return only a URef
            panic!("new_uref should never return anything other than a Key::URef")
        }
    }

    pub fn store_function_at_hash(&mut self, contract: StoredValue) -> Result<[u8; 32], Error> {
        let new_hash = self.new_function_address()?;
        self.validate_value(&contract)?;
        let hash_key = Key::Hash(new_hash);
        self.state.borrow_mut().write(hash_key, contract);
        Ok(new_hash)
    }

    pub fn insert_key(&mut self, name: String, key: Key) {
        if let Key::URef(uref) = key {
            self.insert_uref(uref);
        }
        self.named_keys.insert(name, key);
    }

    pub fn insert_uref(&mut self, uref: URef) {
        if let Some(rights) = uref.access_rights() {
            let entry = self
                .access_rights
                .entry(uref.addr())
                .or_insert_with(|| std::iter::empty().collect());
            entry.insert(rights);
        }
    }

    pub fn effect(&self) -> ExecutionEffect {
        self.state.borrow_mut().effect()
    }

    /// Validates whether keys used in the `value` are not forged.
    fn validate_value(&self, value: &StoredValue) -> Result<(), Error> {
        match value {
            StoredValue::CLValue(cl_value) => match cl_value.cl_type() {
                CLType::Bool
                | CLType::I32
                | CLType::I64
                | CLType::U8
                | CLType::U32
                | CLType::U64
                | CLType::U128
                | CLType::U256
                | CLType::U512
                | CLType::Unit
                | CLType::String
                | CLType::Option(_)
                | CLType::List(_)
                | CLType::FixedList(..)
                | CLType::Result { .. }
                | CLType::Map { .. }
                | CLType::Tuple1(_)
                | CLType::Tuple3(_)
                | CLType::Any => Ok(()),
                CLType::Key => {
                    let key: Key = cl_value.to_owned().into_t()?; // TODO: optimize?
                    self.validate_key(&key)
                }
                CLType::URef => {
                    let uref: URef = cl_value.to_owned().into_t()?; // TODO: optimize?
                    self.validate_uref(&uref)
                }
                tuple @ CLType::Tuple2(_) if *tuple == types::named_key_type() => {
                    let (_name, key): (String, Key) = cl_value.to_owned().into_t()?; // TODO: optimize?
                    self.validate_key(&key)
                }
                CLType::Tuple2(_) => Ok(()),
            },
            StoredValue::Account(account) => {
                // This should never happen as accounts can't be created by contracts.
                // I am putting this here for the sake of completeness.
                account
                    .named_keys()
                    .values()
                    .try_for_each(|key| self.validate_key(key))
            }
            StoredValue::Contract(contract) => contract
                .named_keys()
                .values()
                .try_for_each(|key| self.validate_key(key)),
        }
    }

    /// Validates whether key is not forged (whether it can be found in the
    /// `named_keys`) and whether the version of a key that contract wants
    /// to use, has access rights that are less powerful than access rights'
    /// of the key in the `named_keys`.
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
        if let Some(known_rights) = self.access_rights.get(&uref.addr()) {
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

    pub fn deserialize_keys(&self, bytes: Vec<u8>) -> Result<Vec<Key>, Error> {
        let keys: Vec<Key> = bytesrepr::deserialize(bytes)?;
        keys.iter().try_for_each(|k| self.validate_key(k))?;
        Ok(keys)
    }

    pub fn deserialize_urefs(&self, bytes: Vec<u8>) -> Result<Vec<URef>, Error> {
        let keys: Vec<URef> = bytesrepr::deserialize(bytes)?;
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

    /// Tests whether reading from the `key` is valid.
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

    /// Tests whether writing to `key` is valid.
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
    pub fn add_gs(&mut self, key: Key, value: StoredValue) -> Result<(), Error> {
        self.validate_addable(&key)?;
        self.validate_key(&key)?;
        self.validate_value(&value)?;
        self.add_unsafe(key, value)
    }

    pub fn add_ls(&mut self, key_bytes: &[u8], cl_value: CLValue) -> Result<(), Error> {
        let seed = self.seed();
        let key = Key::local(seed, key_bytes);
        self.add_unsafe(key, StoredValue::CLValue(cl_value))
    }

    fn add_unsafe(&mut self, key: Key, value: StoredValue) -> Result<(), Error> {
        match self.state.borrow_mut().add(self.correlation_id, key, value) {
            Err(storage_error) => Err(storage_error.into()),
            Ok(AddResult::Success) => Ok(()),
            Ok(AddResult::KeyNotFound(key)) => Err(Error::KeyNotFound(key)),
            Ok(AddResult::TypeMismatch(type_mismatch)) => Err(Error::TypeMismatch(type_mismatch)),
            Ok(AddResult::Serialization(error)) => Err(Error::BytesRepr(error)),
        }
    }

    pub fn add_associated_key(
        &mut self,
        public_key: PublicKey,
        weight: Weight,
    ) -> Result<(), Error> {
        // Check permission to modify associated keys
        if !self.is_valid_context() {
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
        let account = {
            let mut account: Account = self.read_gs_typed(&key)?;
            // Exit early in case of error without updating global state
            account
                .add_associated_key(public_key, weight)
                .map_err(Error::from)?;
            account
        };

        let account_value = self.account_to_validated_value(account)?;

        self.state.borrow_mut().write(key, account_value);

        Ok(())
    }

    pub fn remove_associated_key(&mut self, public_key: PublicKey) -> Result<(), Error> {
        // Check permission to modify associated keys
        if !self.is_valid_context() {
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

        let account_value = self.account_to_validated_value(account)?;

        self.state.borrow_mut().write(key, account_value);

        Ok(())
    }

    pub fn update_associated_key(
        &mut self,
        public_key: PublicKey,
        weight: Weight,
    ) -> Result<(), Error> {
        // Check permission to modify associated keys
        if !self.is_valid_context() {
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

        let account_value = self.account_to_validated_value(account)?;

        self.state.borrow_mut().write(key, account_value);

        Ok(())
    }

    pub fn set_action_threshold(
        &mut self,
        action_type: ActionType,
        threshold: Weight,
    ) -> Result<(), Error> {
        // Check permission to modify associated keys
        if !self.is_valid_context() {
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

        let account_value = self.account_to_validated_value(account)?;

        self.state.borrow_mut().write(key, account_value);

        Ok(())
    }

    pub fn upgrade_contract_at_uref(
        &mut self,
        key: Key,
        bytes: Vec<u8>,
        named_keys: BTreeMap<String, Key>,
    ) -> Result<(), Error> {
        let protocol_version = self.protocol_version();
        let contract = Contract::new(bytes, named_keys, protocol_version);
        let contract = StoredValue::Contract(contract);

        self.validate_writeable(&key)?;
        self.validate_key(&key)?;

        self.state.borrow_mut().write(key, contract);
        Ok(())
    }

    pub fn protocol_data(&self) -> ProtocolData {
        self.protocol_data
    }

    /// Attenuates URef for a given account.
    ///
    /// If the account is system account, then given URef receives
    /// full rights (READ_ADD_WRITE). Otherwise READ access is returned.
    pub(crate) fn attenuate_uref(&mut self, uref: URef) -> URef {
        attenuate_uref_for_account(&self.account(), uref)
    }

    /// Creates validated instance of `StoredValue` from `account`.
    fn account_to_validated_value(&self, account: Account) -> Result<StoredValue, Error> {
        let value = StoredValue::Account(account);
        self.validate_value(&value)?;
        Ok(value)
    }

    /// Checks if the account context is valid.
    fn is_valid_context(&self) -> bool {
        self.base_key() == Key::Account(self.account().pub_key())
    }

    /// Gets main purse id
    pub fn get_main_purse(&self) -> Result<PurseId, Error> {
        if !self.is_valid_context() {
            return Err(Error::InvalidContext);
        }
        Ok(self.account().purse_id())
    }
}
