use super::URefAddr;
use common::bytesrepr::deserialize;
use common::key::{AccessRights, Key};
use common::value::account::Account;
use common::value::Value;
use execution::Error;
use std::collections::{BTreeMap, HashMap, HashSet};

/// Holds information specific to the deployed contract.
pub struct RuntimeContext<'a> {
    // Enables look up of specific uref based on human-readable name
    uref_lookup: &'a mut BTreeMap<String, Key>,
    // Used to check uref is known before use (prevents forging urefs)
    known_urefs: HashMap<URefAddr, HashSet<AccessRights>>,
    account: &'a Account,
    // Key pointing to the entity we are currently running
    //(could point at an account or contract in the global state)
    base_key: Key,
    gas_limit: u64,
}

impl<'a> RuntimeContext<'a> {
    pub fn new(
        uref_lookup: &'a mut BTreeMap<String, Key>,
        known_urefs: HashMap<URefAddr, HashSet<AccessRights>>,
        account: &'a Account,
        base_key: Key,
        gas_limit: u64,
    ) -> Self {
        RuntimeContext {
            uref_lookup,
            known_urefs,
            account,
            base_key,
            gas_limit,
        }
    }

    pub fn get_uref(&self, name: &str) -> Option<&Key> {
        self.uref_lookup.get(name)
    }

    pub fn contains_uref(&self, name: &str) -> bool {
        self.uref_lookup.contains_key(name)
    }

    pub fn add_urefs(&mut self, urefs_map: HashMap<URefAddr, HashSet<AccessRights>>) {
        self.known_urefs.extend(urefs_map);
    }

    pub fn account(&self) -> &'a Account {
        self.account
    }

    pub fn gas_limit(&self) -> u64 {
        self.gas_limit
    }

    pub fn base_key(&self) -> Key {
        self.base_key
    }

    // TODO: Accept `Validated<Key>` instead of plain `Key` type.
    pub fn insert_named_uref(&mut self, name: String, key: Key) {
        self.insert_uref(key);
        self.uref_lookup.insert(name, key);
    }

    pub fn insert_uref(&mut self, key: Key) {
        if let Key::URef(raw_addr, rights) = key {
            let entry_rights = self
                .known_urefs
                .entry(raw_addr)
                .or_insert_with(|| std::iter::empty().collect());
            entry_rights.insert(rights);
        }
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
            | Value::ListString(_) => Ok(()),
            Value::NamedKey(_, key) => self.validate_key(&key),
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
        match key {
            Key::URef(raw_addr, new_rights) => {
                self.known_urefs
                    .get(raw_addr) // Check if we `key` is known
                    .map(|known_rights| {
                        known_rights
                            .iter()
                            .any(|right| *right & *new_rights == *new_rights)
                    }) // are we allowed to use it this way?
                    .map(|_| ()) // at this point we know it's valid to use `key`
                    .ok_or_else(|| Error::ForgedReference(*key)) // otherwise `key` is forged
            }
            _ => Ok(()),
        }
    }

    pub fn deserialize_key(&self, bytes: &[u8]) -> Result<Key, Error> {
        let key: Key = deserialize(bytes)?;
        self.validate_key(&key).map(|_| key)
    }

    pub fn deserialize_keys(&self, bytes: &[u8]) -> Result<Vec<Key>, Error> {
        let keys: Vec<Key> = deserialize(bytes)?;
        keys.iter().try_for_each(|k| self.validate_key(k))?;
        Ok(keys)
    }
}
