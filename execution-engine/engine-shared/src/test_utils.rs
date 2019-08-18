//! Some functions to use in tests.
use std::collections::btree_map::BTreeMap;

use parity_wasm::builder::ModuleBuilder;
use parity_wasm::elements::{MemorySection, MemoryType, Module, Section, Serialize};

use contract_ffi::bytesrepr::{self, FromBytes, ToBytes};
use contract_ffi::key::Key;
use contract_ffi::uref::{AccessRights, URef};
use contract_ffi::value::account::PurseId;
use contract_ffi::value::{Account, Value};

/// Returns `true` if a we can serialize and then deserialize a value
pub fn test_serialization_roundtrip<T>(t: &T) -> bool
where
    T: ToBytes + FromBytes + PartialEq + std::fmt::Debug,
{
    match bytesrepr::deserialize::<T>(&ToBytes::to_bytes(t).expect("Unable to serialize data"))
        .map(|r| r == *t)
        .ok()
    {
        Some(true) => true,
        Some(false) => false,
        None => false,
    }
}

/// Returns the serialized form of an empty Wasm Module
pub fn create_empty_wasm_module_bytes() -> Vec<u8> {
    let mem_section = MemorySection::with_entries(vec![MemoryType::new(16, Some(64))]);
    let section = Section::Memory(mem_section);
    let parity_module: Module = ModuleBuilder::new().with_section(section).build();
    let mut wasm_bytes = vec![];
    parity_module.serialize(&mut wasm_bytes).unwrap();
    wasm_bytes
}

/// Returns an account value paired with its key
pub fn mocked_account(account_addr: [u8; 32]) -> Vec<(Key, Value)> {
    let purse_id = PurseId::new(URef::new([0u8; 32], AccessRights::READ_ADD_WRITE));
    let account = Account::create(account_addr, BTreeMap::new(), purse_id);
    vec![(Key::Account(account_addr), Value::Account(account))]
}
