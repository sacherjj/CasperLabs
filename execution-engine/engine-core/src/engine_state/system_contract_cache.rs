use std::collections::HashMap;
use std::sync::{Arc, RwLock};

use parity_wasm::elements::Module;

use contract_ffi::uref::URef;

/// A cache of deserialized contracts.
#[derive(Clone, Default, Debug)]
pub struct SystemContractCache(Arc<RwLock<HashMap<URef, Module>>>);

impl SystemContractCache {
    /// Returns `true` if the cache has a contract corresponding to `uref`.
    pub fn has(&self, uref: &URef) -> bool {
        let guarded_map = self.0.read().unwrap();
        let uref = uref.remove_access_rights();
        guarded_map.contains_key(&uref)
    }

    /// Inserts `contract` into the cache under `uref`.
    ///
    /// If the cache did not have this key present, `None` is returned.
    ///
    /// If the cache did have this key present, the value is updated, and the old value is returned.
    pub fn insert(&self, uref: URef, contract: Module) -> Option<Module> {
        let mut guarded_map = self.0.write().unwrap();
        let uref = uref.remove_access_rights();
        guarded_map.insert(uref, contract)
    }

    /// Returns a clone of the contract corresponding to `uref`.
    pub fn get(&self, uref: &URef) -> Option<Module> {
        let guarded_map = self.0.read().unwrap();
        let uref = uref.remove_access_rights();
        guarded_map.get(&uref).cloned()
    }
}
