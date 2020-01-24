use std::{
    collections::HashMap,
    sync::{Arc, RwLock},
};

use parity_wasm::elements::Module;

use types::URef;

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

#[cfg(test)]
mod tests {
    use std::sync::Mutex;

    use lazy_static::lazy_static;
    use parity_wasm::elements::{Module, ModuleNameSection, NameSection, Section};

    use types::{AccessRights, URef};

    use crate::{
        engine_state::system_contract_cache::SystemContractCache,
        execution::{AddressGenerator, AddressGeneratorBuilder},
    };

    lazy_static! {
        static ref ADDRESS_GENERATOR: Mutex<AddressGenerator> = Mutex::new(
            AddressGeneratorBuilder::new()
                .seed_with(b"test_seed")
                .build()
        );
    }

    #[test]
    pub fn should_insert_module() {
        let reference = {
            let mut address_generator = ADDRESS_GENERATOR.lock().unwrap();
            let address = address_generator.create_address();
            URef::new(address, AccessRights::READ_ADD_WRITE)
        };
        let module = Module::default();

        let cache = SystemContractCache::default();

        let result = cache.insert(reference, module);

        assert!(result.is_none())
    }

    #[test]
    pub fn should_has_false() {
        let cache = SystemContractCache::default();
        let reference = {
            let mut address_generator = ADDRESS_GENERATOR.lock().unwrap();
            let address = address_generator.create_address();
            URef::new(address, AccessRights::READ_ADD_WRITE)
        };

        assert!(!cache.has(&reference))
    }

    #[test]
    pub fn should_has_true() {
        let cache = SystemContractCache::default();
        let reference = {
            let mut address_generator = ADDRESS_GENERATOR.lock().unwrap();
            let address = address_generator.create_address();
            URef::new(address, AccessRights::READ_ADD_WRITE)
        };
        let module = Module::default();

        cache.insert(reference, module);

        assert!(cache.has(&reference))
    }

    #[test]
    pub fn should_has_true_normalized_has() {
        let cache = SystemContractCache::default();
        let reference = {
            let mut address_generator = ADDRESS_GENERATOR.lock().unwrap();
            let address = address_generator.create_address();
            URef::new(address, AccessRights::READ_ADD_WRITE)
        };
        let module = Module::default();

        cache.insert(reference, module);

        assert!(cache.has(&reference.with_access_rights(AccessRights::ADD_WRITE)))
    }

    #[test]
    pub fn should_has_true_normalized_insert() {
        let cache = SystemContractCache::default();
        let reference = {
            let mut address_generator = ADDRESS_GENERATOR.lock().unwrap();
            let address = address_generator.create_address();
            URef::new(address, AccessRights::READ_ADD_WRITE)
        };
        let module = Module::default();

        cache.insert(
            reference.with_access_rights(AccessRights::ADD_WRITE),
            module,
        );

        assert!(cache.has(&reference))
    }

    #[test]
    pub fn should_get_none() {
        let reference = {
            let mut address_generator = ADDRESS_GENERATOR.lock().unwrap();
            let address = address_generator.create_address();
            URef::new(address, AccessRights::READ_ADD_WRITE)
        };
        let cache = SystemContractCache::default();

        let result = cache.get(&reference);

        assert!(result.is_none())
    }

    #[test]
    pub fn should_get_module() {
        let cache = SystemContractCache::default();
        let reference = {
            let mut address_generator = ADDRESS_GENERATOR.lock().unwrap();
            let address = address_generator.create_address();
            URef::new(address, AccessRights::READ_ADD_WRITE)
        };
        let module = Module::default();

        cache.insert(reference, module.clone());

        let result = cache.get(&reference);

        assert_eq!(result, Some(module))
    }

    #[test]
    pub fn should_get_module_normalized_get() {
        let cache = SystemContractCache::default();
        let reference = {
            let mut address_generator = ADDRESS_GENERATOR.lock().unwrap();
            let address = address_generator.create_address();
            URef::new(address, AccessRights::READ_ADD_WRITE)
        };
        let module = Module::default();

        cache.insert(reference, module.clone());

        let result = cache.get(&reference.remove_access_rights());

        assert_eq!(result, Some(module.clone()));

        let result = cache.get(&reference.with_access_rights(AccessRights::ADD_WRITE));

        assert_eq!(result, Some(module))
    }

    #[test]
    pub fn should_get_module_normalized_insert() {
        let cache = SystemContractCache::default();
        let reference = {
            let mut address_generator = ADDRESS_GENERATOR.lock().unwrap();
            let address = address_generator.create_address();
            URef::new(address, AccessRights::READ_ADD_WRITE)
        };
        let module = Module::default();

        cache.insert(
            reference.with_access_rights(AccessRights::ADD_WRITE),
            module.clone(),
        );

        let result = cache.get(&reference);

        assert_eq!(result, Some(module.clone()));

        let result = cache.get(&reference.remove_access_rights());

        assert_eq!(result, Some(module))
    }

    #[test]
    pub fn should_update_module() {
        let cache = SystemContractCache::default();
        let reference = {
            let mut address_generator = ADDRESS_GENERATOR.lock().unwrap();
            let address = address_generator.create_address();
            URef::new(address, AccessRights::READ_ADD_WRITE)
        };
        let initial_module = Module::default();
        let updated_module = {
            let section = NameSection::Module(ModuleNameSection::new("a_mod"));
            let sections = vec![Section::Name(section)];
            Module::new(sections)
        };

        assert_ne!(initial_module, updated_module);

        let result = cache.insert(reference, initial_module.clone());

        assert!(result.is_none());

        let result = cache.insert(reference, updated_module.clone());

        assert_eq!(result, Some(initial_module));

        let result = cache.get(&reference);

        assert_eq!(result, Some(updated_module))
    }

    #[test]
    pub fn should_update_module_normalized() {
        let cache = SystemContractCache::default();
        let reference = {
            let mut address_generator = ADDRESS_GENERATOR.lock().unwrap();
            let address = address_generator.create_address();
            URef::new(address, AccessRights::READ_ADD_WRITE)
        };
        let initial_module = Module::default();
        let updated_module = {
            let section = NameSection::Module(ModuleNameSection::new("a_mod"));
            let sections = vec![Section::Name(section)];
            Module::new(sections)
        };

        assert_ne!(initial_module, updated_module);

        let result = cache.insert(reference, initial_module.clone());

        assert!(result.is_none());

        let result = cache.insert(
            reference.with_access_rights(AccessRights::ADD_WRITE),
            updated_module.clone(),
        );

        assert_eq!(result, Some(initial_module));

        let result = cache.get(&reference);

        assert_eq!(result, Some(updated_module))
    }
}
