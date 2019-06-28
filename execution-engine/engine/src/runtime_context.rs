use std::cell::RefCell;
use std::collections::{BTreeMap, HashMap, HashSet};
use std::convert::{TryFrom, TryInto};
use std::fmt::Display;
use std::rc::Rc;

use blake2::digest::{Input, VariableOutput};
use blake2::VarBlake2b;
use rand::RngCore;
use rand_chacha::ChaChaRng;

use common::bytesrepr::{deserialize, ToBytes};
use common::key::{Key, LOCAL_SEED_SIZE};
use common::uref::{AccessRights, URef};
use common::value::account::{
    Account, ActionType, AddKeyFailure, BlockTime, PublicKey, RemoveKeyFailure,
    SetThresholdFailure, Weight,
};
use common::value::{Contract, Value};
use shared::newtypes::{CorrelationId, Validated};
use storage::global_state::StateReader;

use engine_state::execution_effect::ExecutionEffect;
use execution::Error;
use tracking_copy::{AddResult, TrackingCopy};
use URefAddr;

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
    // Key of the caller.
    caller_key: Option<PublicKey>,
    // Key pointing to the entity we are currently running
    //(could point at an account or contract in the global state)
    base_key: Key,
    blocktime: BlockTime,
    gas_limit: u64,
    gas_counter: u64,
    fn_store_id: u32,
    rng: ChaChaRng,
    protocol_version: u64,
    correlation_id: CorrelationId,
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
        account: &'a Account,
        caller_key: Option<PublicKey>,
        base_key: Key,
        blocktime: BlockTime,
        gas_limit: u64,
        gas_counter: u64,
        fn_store_id: u32,
        rng: ChaChaRng,
        protocol_version: u64,
        correlation_id: CorrelationId,
    ) -> Self {
        RuntimeContext {
            state,
            uref_lookup,
            known_urefs,
            args,
            caller_key,
            account,
            blocktime,
            base_key,
            gas_limit,
            gas_counter,
            fn_store_id,
            rng,
            protocol_version,
            correlation_id,
        }
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
        let validated_uref = Validated::new(contract_key, Validated::valid)?;
        let validated_value =
            Validated::new(Value::Contract(contract), |value| self.validate_keys(value))?;

        self.state
            .borrow_mut()
            .write(validated_uref, validated_value);

        Ok(())
    }

    /// Remove URef from the `known_urefs` map of the current context.
    /// It removes both from the ephemeral map (RuntimeContext::known_urefs) but also
    /// persistable map (one that is found in the TrackingCopy/GlobalState).
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
                let mut contract: Contract = self.read_gs_typed(&contract_uref)?;
                self.uref_lookup.remove(name);
                self.remove_uref_from_contract(contract_uref, contract, name)
            }
            contract_hash @ Key::Hash(_) => {
                let mut contract: Contract = self.read_gs_typed(&contract_hash)?;
                self.uref_lookup.remove(name);
                self.remove_uref_from_contract(contract_hash, contract, name)
            }
            contract_local @ Key::Local(_) => {
                let mut contract: Contract = self.read_gs_typed(&contract_local)?;
                self.uref_lookup.remove(name);
                self.remove_uref_from_contract(contract_local, contract, name)
            }
        }
    }

    pub fn get_caller(&self) -> Option<PublicKey> {
        self.caller_key
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

    pub fn rng(&mut self) -> &mut ChaChaRng {
        &mut self.rng
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

    /// Generates new function address.
    /// Function address is deterministic. It is a hash of public key, nonce and `fn_store_id`,
    /// which is a counter that is being incremented after every function generation.
    /// If function address was based only on account's public key and deploy's nonce,
    /// then all function addresses generated within one deploy would have been the same.
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
            self.rng.fill_bytes(&mut addr);
            URef::new(addr, AccessRights::READ_ADD_WRITE)
        };
        let key = Key::URef(uref);
        self.insert_uref(uref);
        self.write_gs(key, value)?;
        Ok(key)
    }

    /// Adds `key` to the map of named keys of current context.
    pub fn add_uref(&mut self, name: String, key: Key) -> Result<(), Error> {
        let base_key = self.base_key();
        self.add_gs(base_key, Value::NamedKey(name.clone(), key))?;
        let validated_key = Validated::new(key, Validated::valid)?;
        self.insert_named_uref(name, validated_key);
        Ok(())
    }

    pub fn read_ls(&mut self, key: &[u8]) -> Result<Option<Value>, Error> {
        let seed = self.seed();
        let key = Key::local(seed, key);
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

    /// Validates whether key is not forged (whether it can be found in the `known_urefs`)
    /// and whether the version of a key that contract wants to use, has access rights
    /// that are less powerful than access rights' of the key in the `known_urefs`.
    pub fn validate_key(&self, key: &Key) -> Result<(), Error> {
        let uref = match key {
            Key::URef(uref) => uref,
            _ => return Ok(()),
        };
        if let Some(new_rights) = uref.access_rights() {
            self.known_urefs
                .get(&uref.addr()) // Check if the `key` is known
                .map(|known_rights| {
                    known_rights
                        .iter()
                        .any(|right| *right & new_rights == new_rights)
                }) // are we allowed to use it this way?
                .map(|_| ()) // at this point we know it's valid to use `key`
                .ok_or_else(|| Error::ForgedReference(*key)) // otherwise `key` is forged
        } else {
            Ok(())
        }
    }

    pub fn deserialize_keys(&self, bytes: &[u8]) -> Result<Vec<Key>, Error> {
        let keys: Vec<Key> = deserialize(bytes)?;
        keys.iter().try_for_each(|k| self.validate_key(k))?;
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

    /// Adds `value` to the `key`. The premise for being able to `add` value is that
    /// the type of it [value] can be added (is a Monoid). If the values can't be added,
    /// either because they're not a Monoid or if the value stored under `key` has different type,
    /// then `TypeMismatch` errors is returned.
    pub fn add_gs(&mut self, key: Key, value: Value) -> Result<(), Error> {
        let validated_key = Validated::new(key, |k| {
            self.validate_addable(&k).and(self.validate_key(&k))
        })?;
        let validated_value = Validated::new(value, |v| self.validate_keys(&v))?;
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

#[cfg(test)]
mod tests {
    use std::cell::RefCell;
    use std::collections::{BTreeMap, HashMap, HashSet};
    use std::iter::once;
    use std::rc::Rc;

    use rand::RngCore;
    use rand_chacha::ChaChaRng;

    use common::key::{Key, LOCAL_SEED_SIZE};
    use common::uref::{AccessRights, URef};
    use common::value::{self, Account, Contract, Value};
    use shared::transform::Transform;
    use storage::global_state::in_memory::InMemoryGlobalState;
    use storage::global_state::{CommitResult, History};

    use super::{Error, RuntimeContext, URefAddr, Validated};
    use common::value::account::{
        AccountActivity, ActionType, AddKeyFailure, AssociatedKeys, BlockTime, PublicKey, PurseId,
        RemoveKeyFailure, SetThresholdFailure, Weight,
    };
    use execution::{create_rng, vec_key_rights_to_map};
    use shared::newtypes::CorrelationId;
    use tracking_copy::TrackingCopy;

    fn mock_tc(init_key: Key, init_account: value::Account) -> TrackingCopy<InMemoryGlobalState> {
        let correlation_id = CorrelationId::new();
        let mut hist = InMemoryGlobalState::empty().unwrap();
        let root_hash = hist.root_hash;
        let transform = Transform::Write(value::Value::Account(init_account.clone()));

        let mut m = HashMap::new();
        m.insert(init_key, transform);
        let commit_result = hist
            .commit(correlation_id, root_hash, m)
            .expect("Creation of mocked account should be a success.");

        let new_hash = match commit_result {
            CommitResult::Success(new_hash) => new_hash,
            other => panic!("Commiting changes to test History failed: {:?}.", other),
        };

        let reader = hist
            .checkout(new_hash)
            .expect("Checkout should not throw errors.")
            .expect("Root hash should exist.");

        TrackingCopy::new(reader)
    }

    fn mock_account(addr: [u8; 32]) -> (Key, value::Account) {
        let associated_keys = AssociatedKeys::new(PublicKey::new(addr), Weight::new(1));
        let account = value::account::Account::new(
            addr,
            0,
            BTreeMap::new(),
            PurseId::new(URef::new([0u8; 32], AccessRights::READ_ADD_WRITE)),
            associated_keys,
            Default::default(),
            AccountActivity::new(BlockTime(0), BlockTime(100)),
        );
        let key = Key::Account(addr);

        (key, account)
    }

    // create random account key.
    fn random_account_key<G: RngCore>(entropy_source: &mut G) -> Key {
        let mut key = [0u8; 32];
        entropy_source.fill_bytes(&mut key);
        Key::Account(key)
    }

    // create random contract key.
    fn random_contract_key<G: RngCore>(entropy_source: &mut G) -> Key {
        let mut key = [0u8; 32];
        entropy_source.fill_bytes(&mut key);
        Key::Hash(key)
    }

    // Create random URef Key.
    fn random_uref_key<G: RngCore>(entropy_source: &mut G, rights: AccessRights) -> Key {
        let mut key = [0u8; 32];
        entropy_source.fill_bytes(&mut key);
        Key::URef(URef::new(key, rights))
    }

    fn random_local_key<G: RngCore>(entropy_source: &mut G, seed: [u8; LOCAL_SEED_SIZE]) -> Key {
        let mut key = [0u8; 64];
        entropy_source.fill_bytes(&mut key);
        Key::local(seed, &key)
    }

    fn mock_runtime_context<'a>(
        account: &'a Account,
        base_key: Key,
        uref_map: &'a mut BTreeMap<String, Key>,
        known_urefs: HashMap<URefAddr, HashSet<AccessRights>>,
        rng: ChaChaRng,
    ) -> RuntimeContext<'a, InMemoryGlobalState> {
        let tc = mock_tc(base_key, account.clone());
        RuntimeContext::new(
            Rc::new(RefCell::new(tc)),
            uref_map,
            known_urefs,
            Vec::new(),
            &account,
            None,
            base_key,
            BlockTime(0),
            0,
            0,
            0,
            rng,
            1,
            CorrelationId::new(),
        )
    }

    #[allow(clippy::assertions_on_constants)]
    fn assert_forged_reference<T>(result: Result<T, Error>) {
        match result {
            Err(Error::ForgedReference(_)) => assert!(true),
            _ => panic!("Error. Test should have failed with ForgedReference error but didn't."),
        }
    }

    #[allow(clippy::assertions_on_constants)]
    fn assert_invalid_access<T: std::fmt::Debug>(
        result: Result<T, Error>,
        expecting: AccessRights,
    ) {
        match result {
            Err(Error::InvalidAccess { required }) if required == expecting => assert!(true),
            other => panic!(
                "Error. Test should have failed with InvalidAccess error but didn't: {:?}.",
                other
            ),
        }
    }

    fn test<T, F>(
        known_urefs: HashMap<URefAddr, HashSet<AccessRights>>,
        query: F,
    ) -> Result<T, Error>
    where
        F: Fn(RuntimeContext<InMemoryGlobalState>) -> Result<T, Error>,
    {
        let base_acc_addr = [0u8; 32];
        let (key, account) = mock_account(base_acc_addr);
        let mut uref_map = BTreeMap::new();
        let chacha_rng = create_rng(base_acc_addr, 0);
        let runtime_context =
            mock_runtime_context(&account, key, &mut uref_map, known_urefs, chacha_rng);
        query(runtime_context)
    }

    #[test]
    fn use_uref_valid() {
        // Test fixture
        let mut rng = rand::thread_rng();
        let uref = random_uref_key(&mut rng, AccessRights::READ_WRITE);
        let known_urefs = vec_key_rights_to_map(vec![uref]);
        // Use uref as the key to perform an action on the global state.
        // This should succeed because the uref is valid.
        let query_result = test(known_urefs, |mut rc| rc.write_gs(uref, Value::Int32(43)));
        query_result.expect("writing using valid uref should succeed");
    }

    #[test]
    fn use_uref_forged() {
        // Test fixture
        let mut rng = rand::thread_rng();
        let uref = random_uref_key(&mut rng, AccessRights::READ_WRITE);
        let known_urefs = HashMap::new();
        let query_result = test(known_urefs, |mut rc| rc.write_gs(uref, Value::Int32(43)));

        assert_forged_reference(query_result);
    }

    #[test]
    fn store_contract_with_uref_valid() {
        let mut rng = rand::thread_rng();
        let uref = random_uref_key(&mut rng, AccessRights::READ_WRITE);
        let known_urefs = vec_key_rights_to_map(vec![uref]);

        let contract = Value::Contract(Contract::new(
            Vec::new(),
            once(("ValidURef".to_owned(), uref)).collect(),
            1,
        ));

        let query_result = test(known_urefs, |mut rc| {
            let contract_addr = rc
                .store_contract(contract.clone())
                .expect("Storing contract with valid URefs should succeed.");
            let contract_key = Key::Hash(contract_addr);
            rc.read_gs(&contract_key)
        });

        let contract_gs = query_result
            .expect("Reading contract from the GS should work.")
            .expect("Contract should be found.");

        assert_eq!(contract, contract_gs);
    }

    #[test]
    fn store_contract_with_uref_forged() {
        let mut rng = rand::thread_rng();
        let uref = random_uref_key(&mut rng, AccessRights::READ_WRITE);
        let contract = Value::Contract(Contract::new(
            Vec::new(),
            once(("ForgedURef".to_owned(), uref)).collect(),
            1,
        ));

        let query_result = test(HashMap::new(), |mut rc| rc.store_contract(contract.clone()));

        assert_forged_reference(query_result);
    }

    #[test]
    fn store_contract_under_uref_valid() {
        // Test that storing contract under URef that is known and has WRITE access works.
        let mut rng = rand::thread_rng();
        let contract_uref = random_uref_key(&mut rng, AccessRights::READ_WRITE);
        let known_urefs = vec_key_rights_to_map(vec![contract_uref]);
        let contract: Value = Contract::new(
            Vec::new(),
            once(("ValidURef".to_owned(), contract_uref)).collect(),
            1,
        )
        .into();

        let query_result = test(known_urefs, |mut rc| {
            rc.write_gs(contract_uref, contract.clone())
                .expect("Storing contract under known and writeable URef should work.");
            rc.read_gs(&contract_uref)
        });

        let contract_gs = query_result
            .expect("Reading contract from the GS should work.")
            .expect("Contract should be found.");

        assert_eq!(contract, contract_gs);
    }

    #[test]
    fn store_contract_under_uref_forged() {
        // Test that storing contract under URef that is not known fails with ForgedReference error.
        let mut rng = rand::thread_rng();
        let contract_uref = random_uref_key(&mut rng, AccessRights::READ_WRITE);
        let contract: Value = Contract::new(Vec::new(), BTreeMap::new(), 1).into();

        let query_result = test(HashMap::new(), |mut rc| {
            rc.write_gs(contract_uref, contract.clone())
        });

        assert_forged_reference(query_result);
    }

    #[test]
    fn store_contract_uref_invalid_access() {
        // Test that storing contract under URef that is known but is not writeable fails.
        let mut rng = rand::thread_rng();
        let contract_uref = random_uref_key(&mut rng, AccessRights::READ);
        let known_urefs = vec_key_rights_to_map(vec![contract_uref]);
        let contract: Value = Contract::new(Vec::new(), BTreeMap::new(), 1).into();

        let query_result = test(known_urefs, |mut rc| {
            rc.write_gs(contract_uref, contract.clone())
        });

        assert_invalid_access(query_result, AccessRights::WRITE);
    }

    #[test]
    fn account_key_not_writeable() {
        let mut rng = rand::thread_rng();
        let acc_key = random_account_key(&mut rng);
        let query_result = test(HashMap::new(), |mut rc| {
            rc.write_gs(acc_key, Value::Int32(1))
        });
        assert_invalid_access(query_result, AccessRights::WRITE);
    }

    #[test]
    fn account_key_readable_valid() {
        // Account key is readable if it is a "base" key - current context of the execution.
        let query_result = test(HashMap::new(), |mut rc| {
            let base_key = rc.base_key();

            let result = rc
                .read_gs(&base_key)
                .expect("Account key is readable.")
                .expect("Account is found in GS.");

            assert_eq!(result, Value::Account(rc.account().clone()));
            Ok(())
        });

        assert!(query_result.is_ok());
    }

    #[test]
    fn account_key_readable_invalid() {
        // Account key is NOT readable if it is different than the "base" key.
        let mut rng = rand::thread_rng();
        let other_acc_key = random_account_key(&mut rng);

        let query_result = test(HashMap::new(), |mut rc| rc.read_gs(&other_acc_key));

        assert_invalid_access(query_result, AccessRights::READ);
    }

    #[test]
    fn account_key_addable_valid() {
        // Account key is addable if it is a "base" key - current context of the execution.
        let mut rng = rand::thread_rng();
        let uref = random_uref_key(&mut rng, AccessRights::READ);
        let known_urefs = vec_key_rights_to_map(vec![uref]);
        let query_result = test(known_urefs, |mut rc| {
            let base_key = rc.base_key();
            let uref_name = "NewURef".to_owned();
            let named_key = Value::NamedKey(uref_name.clone(), uref);

            rc.add_gs(base_key, named_key).expect("Adding should work.");

            let named_key_transform = Transform::AddKeys(once((uref_name.clone(), uref)).collect());

            assert_eq!(
                *rc.effect().transforms.get(&base_key).unwrap(),
                named_key_transform
            );

            Ok(())
        });

        assert!(query_result.is_ok());
    }

    #[test]
    fn account_key_addable_invalid() {
        // Account key is NOT addable if it is a "base" key - current context of the execution.
        let mut rng = rand::thread_rng();
        let other_acc_key = random_account_key(&mut rng);

        let query_result = test(HashMap::new(), |mut rc| {
            rc.add_gs(other_acc_key, Value::Int32(1))
        });

        assert_invalid_access(query_result, AccessRights::ADD);
    }

    #[test]
    fn contract_key_readable_valid() {
        // Account key is readable if it is a "base" key - current context of the execution.
        let mut rng = rand::thread_rng();
        let contract_key = random_contract_key(&mut rng);
        let query_result = test(HashMap::new(), |mut rc| rc.read_gs(&contract_key));

        assert!(query_result.is_ok());
    }

    #[test]
    fn contract_key_not_writeable() {
        // Account key is readable if it is a "base" key - current context of the execution.
        let mut rng = rand::thread_rng();
        let contract_key = random_contract_key(&mut rng);
        let query_result = test(HashMap::new(), |mut rc| {
            rc.write_gs(contract_key, Value::Int32(1))
        });

        assert_invalid_access(query_result, AccessRights::WRITE);
    }

    #[test]
    fn contract_key_addable_valid() {
        // Contract key is addable if it is a "base" key - current context of the execution.
        let base_acc_addr = [0u8; 32];
        let (account_key, account) = mock_account(base_acc_addr);
        let mut rng = rand::thread_rng();
        let contract_key = random_contract_key(&mut rng);
        let contract: Value = Contract::new(Vec::new(), BTreeMap::new(), 1).into();
        let tc = Rc::new(RefCell::new(mock_tc(account_key, account.clone())));
        // Store contract in the GlobalState so that we can mainpulate it later.
        tc.borrow_mut().write(
            Validated::new(contract_key, Validated::valid).unwrap(),
            Validated::new(contract, Validated::valid).unwrap(),
        );

        let mut uref_map = BTreeMap::new();
        let uref = random_uref_key(&mut rng, AccessRights::WRITE);
        let known_urefs = vec_key_rights_to_map(vec![uref]);
        let chacha_rng = create_rng(base_acc_addr, 0);

        let mut runtime_context = RuntimeContext::new(
            Rc::clone(&tc),
            &mut uref_map,
            known_urefs,
            Vec::new(),
            &account,
            None,
            contract_key,
            BlockTime(0),
            0,
            0,
            0,
            chacha_rng,
            1,
            CorrelationId::new(),
        );

        let uref_name = "NewURef".to_owned();
        let named_key = Value::NamedKey(uref_name.clone(), uref);

        runtime_context
            .add_gs(contract_key, named_key)
            .expect("Adding should work.");

        let updated_contract: Value =
            Contract::new(Vec::new(), once((uref_name, uref)).collect(), 1).into();

        assert_eq!(
            *tc.borrow().effect().transforms.get(&contract_key).unwrap(),
            Transform::Write(updated_contract)
        );
    }

    #[test]
    fn contract_key_addable_invalid() {
        // Contract key is addable if it is a "base" key - current context of the execution.
        let base_acc_addr = [0u8; 32];
        let (account_key, account) = mock_account(base_acc_addr);
        let mut rng = rand::thread_rng();
        let contract_key = random_contract_key(&mut rng);
        let other_contract_key = random_contract_key(&mut rng);
        let contract: Value = Contract::new(Vec::new(), BTreeMap::new(), 1).into();
        let tc = Rc::new(RefCell::new(mock_tc(account_key, account.clone())));
        // Store contract in the GlobalState so that we can mainpulate it later.
        tc.borrow_mut().write(
            Validated::new(contract_key, Validated::valid).unwrap(),
            Validated::new(contract, Validated::valid).unwrap(),
        );

        let mut uref_map = BTreeMap::new();
        let uref = random_uref_key(&mut rng, AccessRights::WRITE);
        let known_urefs = vec_key_rights_to_map(vec![uref]);
        let chacha_rng = create_rng(base_acc_addr, 0);
        let mut runtime_context = RuntimeContext::new(
            Rc::clone(&tc),
            &mut uref_map,
            known_urefs,
            Vec::new(),
            &account,
            None,
            other_contract_key,
            BlockTime(0),
            0,
            0,
            0,
            chacha_rng,
            1,
            CorrelationId::new(),
        );

        let uref_name = "NewURef".to_owned();
        let named_key = Value::NamedKey(uref_name.clone(), uref);

        let result = runtime_context.add_gs(contract_key, named_key);

        assert_invalid_access(result, AccessRights::ADD);
    }

    #[test]
    fn uref_key_readable_valid() {
        let mut rng = rand::thread_rng();
        let uref_key = random_uref_key(&mut rng, AccessRights::READ);
        let known_urefs = vec_key_rights_to_map(vec![uref_key]);
        let query_result = test(known_urefs, |mut rc| rc.read_gs(&uref_key));
        assert!(query_result.is_ok());
    }

    #[test]
    fn uref_key_readable_invalid() {
        let mut rng = rand::thread_rng();
        let uref_key = random_uref_key(&mut rng, AccessRights::WRITE);
        let known_urefs = vec_key_rights_to_map(vec![uref_key]);
        let query_result = test(known_urefs, |mut rc| rc.read_gs(&uref_key));
        assert_invalid_access(query_result, AccessRights::READ);
    }

    #[test]
    fn uref_key_writeable_valid() {
        let mut rng = rand::thread_rng();
        let uref_key = random_uref_key(&mut rng, AccessRights::WRITE);
        let known_urefs = vec_key_rights_to_map(vec![uref_key]);
        let query_result = test(known_urefs, |mut rc| rc.write_gs(uref_key, Value::Int32(1)));
        assert!(query_result.is_ok());
    }

    #[test]
    fn uref_key_writeable_invalid() {
        let mut rng = rand::thread_rng();
        let uref_key = random_uref_key(&mut rng, AccessRights::READ);
        let known_urefs = vec_key_rights_to_map(vec![uref_key]);
        let query_result = test(known_urefs, |mut rc| rc.write_gs(uref_key, Value::Int32(1)));
        assert_invalid_access(query_result, AccessRights::WRITE);
    }

    #[test]
    fn uref_key_addable_valid() {
        let mut rng = rand::thread_rng();
        let uref_key = random_uref_key(&mut rng, AccessRights::ADD_WRITE);
        let known_urefs = vec_key_rights_to_map(vec![uref_key]);
        let query_result = test(known_urefs, |mut rc| {
            rc.write_gs(uref_key, Value::Int32(10))
                .expect("Writing to the GlobalState should work.");
            rc.add_gs(uref_key, Value::Int32(1))
        });
        assert!(query_result.is_ok());
    }

    #[test]
    fn uref_key_addable_invalid() {
        let mut rng = rand::thread_rng();
        let uref_key = random_uref_key(&mut rng, AccessRights::WRITE);
        let known_urefs = vec_key_rights_to_map(vec![uref_key]);
        let query_result = test(known_urefs, |mut rc| rc.add_gs(uref_key, Value::Int32(1)));
        assert_invalid_access(query_result, AccessRights::ADD);
    }

    #[test]
    fn local_key_writeable_valid() {
        let known_urefs = HashMap::new();
        let query = |runtime_context: RuntimeContext<InMemoryGlobalState>| {
            let mut rng = rand::thread_rng();
            let seed = runtime_context.seed();
            let key = random_local_key(&mut rng, seed);
            runtime_context.validate_writeable(&key)
        };
        let query_result = test(known_urefs, query);
        assert!(query_result.is_err())
    }

    #[test]
    fn local_key_writeable_invalid() {
        let known_urefs = HashMap::new();
        let query = |runtime_context: RuntimeContext<InMemoryGlobalState>| {
            let mut rng = rand::thread_rng();
            let seed = [1u8; LOCAL_SEED_SIZE];
            let key = random_local_key(&mut rng, seed);
            runtime_context.validate_writeable(&key)
        };
        let query_result = test(known_urefs, query);
        assert!(query_result.is_err())
    }

    #[test]
    fn local_key_readable_valid() {
        let known_urefs = HashMap::new();
        let query = |runtime_context: RuntimeContext<InMemoryGlobalState>| {
            let mut rng = rand::thread_rng();
            let seed = runtime_context.seed();
            let key = random_local_key(&mut rng, seed);
            runtime_context.validate_readable(&key)
        };
        let query_result = test(known_urefs, query);
        assert!(query_result.is_err())
    }

    #[test]
    fn local_key_readable_invalid() {
        let known_urefs = HashMap::new();
        let query = |runtime_context: RuntimeContext<InMemoryGlobalState>| {
            let mut rng = rand::thread_rng();
            let seed = [1u8; LOCAL_SEED_SIZE];
            let key = random_local_key(&mut rng, seed);
            runtime_context.validate_readable(&key)
        };
        let query_result = test(known_urefs, query);
        assert!(query_result.is_err())
    }

    #[test]
    fn local_key_addable_valid() {
        let known_urefs = HashMap::new();
        let query = |runtime_context: RuntimeContext<InMemoryGlobalState>| {
            let mut rng = rand::thread_rng();
            let seed = runtime_context.seed();
            let key = random_local_key(&mut rng, seed);
            runtime_context.validate_addable(&key)
        };
        let query_result = test(known_urefs, query);
        assert!(query_result.is_err())
    }

    #[test]
    fn local_key_addable_invalid() {
        let known_urefs = HashMap::new();
        let query = |runtime_context: RuntimeContext<InMemoryGlobalState>| {
            let mut rng = rand::thread_rng();
            let seed = [1u8; LOCAL_SEED_SIZE];
            let key = random_local_key(&mut rng, seed);
            runtime_context.validate_addable(&key)
        };
        let query_result = test(known_urefs, query);
        assert!(query_result.is_err())
    }

    #[test]
    fn manage_associated_keys() {
        // Testing a valid case only - successfuly added a key, and successfuly removed,
        // making sure `account_dirty` mutated
        let known_urefs = HashMap::new();
        let query = |mut runtime_context: RuntimeContext<InMemoryGlobalState>| {
            let public_key = PublicKey::new([42; 32]);
            let weight = Weight::new(255);

            // Add a key (this doesn't check for all invariants as `add_key`
            // is already tested in different place)
            runtime_context
                .add_associated_key(public_key, weight)
                .expect("Unable to add key");

            let effect = runtime_context.effect();
            let transform = effect.transforms.get(&runtime_context.base_key()).unwrap();
            let account = match transform {
                Transform::Write(Value::Account(account)) => account,
                _ => panic!("Invalid transform operation found"),
            };
            account
                .associated_keys()
                .get(&public_key)
                .expect("Public key wasn't added to associated keys");

            // Remove a key that was already added
            runtime_context
                .remove_associated_key(public_key)
                .expect("Unable to remove key");

            // Verify
            let effect = runtime_context.effect();
            let transform = effect.transforms.get(&runtime_context.base_key()).unwrap();
            let account = match transform {
                Transform::Write(Value::Account(account)) => account,
                _ => panic!("Invalid transform operation found"),
            };

            assert!(account.associated_keys().get(&public_key).is_none());

            // Remove a key that was already removed
            runtime_context
                .remove_associated_key(public_key)
                .expect_err("A non existing key was unexpectedly removed again");

            Ok(())
        };
        let _ = test(known_urefs, query);
    }

    #[test]
    fn action_thresholds_management() {
        // Testing a valid case only - successfuly added a key, and successfuly removed,
        // making sure `account_dirty` mutated
        let known_urefs = HashMap::new();
        let query = |mut runtime_context: RuntimeContext<InMemoryGlobalState>| {
            runtime_context
                .set_action_threshold(ActionType::KeyManagement, Weight::new(253))
                .expect("Unable to set action threshold KeyManagement");
            runtime_context
                .set_action_threshold(ActionType::Deployment, Weight::new(252))
                .expect("Unable to set action threshold Deployment");

            let effect = runtime_context.effect();
            let transform = effect.transforms.get(&runtime_context.base_key()).unwrap();
            let mutated_account = match transform {
                Transform::Write(Value::Account(account)) => account,
                _ => panic!("Invalid transform operation found"),
            };

            assert_eq!(
                mutated_account.action_thresholds().deployment(),
                &Weight::new(252)
            );
            assert_eq!(
                mutated_account.action_thresholds().key_management(),
                &Weight::new(253)
            );

            runtime_context
                .set_action_threshold(ActionType::Deployment, Weight::new(255))
                .expect_err(
                    "Shouldn't be able to set deployment threshold higher than key management",
                );

            Ok(())
        };
        let _ = test(known_urefs, query);
    }

    #[test]
    fn should_verify_ownership_before_adding_key() {
        // Testing a valid case only - successfuly added a key, and successfuly removed,
        // making sure `account_dirty` mutated
        let known_urefs = HashMap::new();
        let query = |mut runtime_context: RuntimeContext<InMemoryGlobalState>| {
            // Overwrites a `base_key` to a different one before doing any operation as account `[0; 32]`
            runtime_context.base_key = Key::Hash([1; 32]);

            let err = runtime_context
                .add_associated_key(PublicKey::new([84; 32]), Weight::new(123))
                .expect_err("This operation should return error");

            match err {
                Error::AddKeyFailure(AddKeyFailure::PermissionDenied) => {}
                e => panic!("Invalid error variant: {:?}", e),
            }

            Ok(())
        };
        let _ = test(known_urefs, query);
    }

    #[test]
    fn should_verify_ownership_before_removing_a_key() {
        // Testing a valid case only - successfuly added a key, and successfuly removed,
        // making sure `account_dirty` mutated
        let known_urefs = HashMap::new();
        let query = |mut runtime_context: RuntimeContext<InMemoryGlobalState>| {
            // Overwrites a `base_key` to a different one before doing any operation as account `[0; 32]`
            runtime_context.base_key = Key::Hash([1; 32]);

            let err = runtime_context
                .remove_associated_key(PublicKey::new([84; 32]))
                .expect_err("This operation should return error");

            match err {
                Error::RemoveKeyFailure(RemoveKeyFailure::PermissionDenied) => {}
                ref e => panic!("Invalid error variant: {:?}", e),
            }

            Ok(())
        };
        let _ = test(known_urefs, query);
    }

    #[test]
    fn should_verify_ownership_before_setting_action_threshold() {
        // Testing a valid case only - successfuly added a key, and successfuly removed,
        // making sure `account_dirty` mutated
        let known_urefs = HashMap::new();
        let query = |mut runtime_context: RuntimeContext<InMemoryGlobalState>| {
            // Overwrites a `base_key` to a different one before doing any operation as account `[0; 32]`
            runtime_context.base_key = Key::Hash([1; 32]);

            let err = runtime_context
                .set_action_threshold(ActionType::Deployment, Weight::new(123))
                .expect_err("This operation should return error");

            match err {
                Error::SetThresholdFailure(SetThresholdFailure::PermissionDeniedError) => {}
                ref e => panic!("Invalid error variant: {:?}", e),
            }

            Ok(())
        };
        let _ = test(known_urefs, query);
    }

    #[test]
    fn can_roundtrip_key_value_pairs_into_local_state() {
        let known_urefs = HashMap::new();
        let query = |mut runtime_context: RuntimeContext<InMemoryGlobalState>| {
            let test_key = b"test_key";
            let test_value = Value::String("test_value".to_string());

            runtime_context
                .write_ls(test_key, test_value.to_owned())
                .expect("should write_ls");

            let result = runtime_context.read_ls(test_key).expect("should read_ls");

            Ok(result == Some(test_value))
        };
        let query_result = test(known_urefs, query).expect("should be ok");
        assert!(query_result)
    }

    #[test]
    fn remove_uref_works() {
        // Test that `remove_uref` removes Key from both ephemeral representation
        // which is one of the current RuntimeContext, and also puts that change
        // into the `TrackingCopy` so that it's later committed to the GlobalState.

        let known_urefs = HashMap::new();
        let base_acc_addr = [0u8; 32];
        let (key, account) = mock_account(base_acc_addr);
        let mut chacha_rng = create_rng(base_acc_addr, 0);
        let uref_name = "Foo".to_owned();
        let uref_key = random_uref_key(&mut chacha_rng, AccessRights::READ);
        let mut uref_map = std::iter::once((uref_name.clone(), uref_key)).collect();
        let mut runtime_context =
            mock_runtime_context(&account, key, &mut uref_map, known_urefs, chacha_rng);

        assert!(runtime_context.contains_uref(&uref_name));
        assert!(runtime_context.remove_uref(&uref_name).is_ok());
        assert!(runtime_context.validate_key(&uref_key).is_err());
        assert!(!runtime_context.contains_uref(&uref_name));
        let effects = runtime_context.effect();
        let transform = effects.transforms.get(&key).unwrap();
        let account = match transform {
            Transform::Write(Value::Account(account)) => account,
            _ => panic!("Invalid transform operation found"),
        };
        assert!(!account.urefs_lookup().contains_key(&uref_name));
    }
}
