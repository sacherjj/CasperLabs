//! Some functions to use in tests.
use std::collections::BTreeMap;

use parity_wasm::{
    builder::ModuleBuilder,
    elements::{MemorySection, MemoryType, Module, Section, Serialize},
};

use engine_wasm_prep::wasm_costs::WasmCosts;
use types::{account::PurseId, AccessRights, Key, URef};

use crate::{account::Account, stored_value::StoredValue};

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
pub fn mocked_account(account_addr: [u8; 32]) -> Vec<(Key, StoredValue)> {
    let purse_id = PurseId::new(URef::new([0u8; 32], AccessRights::READ_ADD_WRITE));
    let account = Account::create(account_addr, BTreeMap::new(), purse_id);
    vec![(Key::Account(account_addr), StoredValue::Account(account))]
}

pub fn wasm_costs_mock() -> WasmCosts {
    WasmCosts {
        regular: 1,
        div: 16,
        mul: 4,
        mem: 2,
        initial_mem: 4096,
        grow_mem: 8192,
        memcpy: 1,
        max_stack_height: 64 * 1024,
        opcodes_mul: 3,
        opcodes_div: 8,
    }
}

pub fn wasm_costs_free() -> WasmCosts {
    WasmCosts {
        regular: 0,
        div: 0,
        mul: 0,
        mem: 0,
        initial_mem: 4096,
        grow_mem: 8192,
        memcpy: 0,
        max_stack_height: 64 * 1024,
        opcodes_mul: 1,
        opcodes_div: 1,
    }
}
