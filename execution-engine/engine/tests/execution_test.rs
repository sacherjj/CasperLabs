extern crate common;
extern crate execution_engine;
extern crate parity_wasm;
extern crate storage;
extern crate wasm_prep;
extern crate wasmi;

use common::bytesrepr::ToBytes;
use common::key::{Key, UREF_SIZE};
use common::value;
use execution_engine::execution::{Runtime, RuntimeContext};
use parity_wasm::builder::module;
use parity_wasm::elements::Module;
use std::collections::{BTreeMap, HashMap};
use storage::gs::{inmem::*, DbReader, ExecutionEffect, TrackingCopy};
use storage::history::*;
use storage::transform::Transform;
use wasm_prep::MAX_MEM_PAGES;
use wasmi::memory_units::Pages;
use wasmi::{MemoryInstance, MemoryRef};

struct MockEnv<'a> {
    key: Key,
    account: value::Account,
    uref_lookup: BTreeMap<String, Key>,
    tc: TrackingCopy<'a, InMemGS>,
    gas_limit: u64,
    memory: MemoryRef,
}

impl<'b> MockEnv<'b> {
    pub fn new(key: Key, account: value::Account, gas_limit: u64, gs: &'b InMemGS) -> Self {
        let tc = TrackingCopy::new(gs);
        let uref_lookup = mock_uref_lookup();
        let memory = MemoryInstance::alloc(Pages(17), Some(Pages(MAX_MEM_PAGES as usize)))
            .expect("Mocked memory should be able to be created.");

        MockEnv {
            key,
            account,
            uref_lookup,
            tc,
            gas_limit,
            memory,
        }
    }

    pub fn runtime<'a>(&'a mut self) -> Runtime<'a, 'b, InMemGS> {
        let context = mock_context(&mut self.uref_lookup, &self.account, self.key);
        Runtime::new(
            self.memory.clone(),
            &mut self.tc,
            mock_module(),
            &self.gas_limit,
            context,
        )
    }

    pub fn memory_manager(&self) -> WasmMemoryManager {
        WasmMemoryManager::new(self.memory.clone())
    }
}

struct WasmMemoryManager {
    memory: MemoryRef,
    offset: usize,
}

impl WasmMemoryManager {
    pub fn new(memory: MemoryRef) -> Self {
        WasmMemoryManager { memory, offset: 0 }
    }

    pub fn write<T: ToBytes>(&mut self, t: T) -> Result<(u32, usize), wasmi::Error> {
        let bytes = t.to_bytes();
        let ptr = self.offset as u32;

        match self.memory.set(ptr, &bytes) {
            Ok(_) => {
                let len = bytes.len();
                self.offset += len;
                Ok((ptr, len))
            }

            Err(e) => Err(e),
        }
    }

    pub fn new_uref<'a, 'b, R: DbReader>(
        &mut self,
        runtime: &mut Runtime<'a, 'b, R>,
    ) -> Result<(u32, usize), wasmi::Trap> {
        let ptr = self.offset as u32;

        match runtime.new_uref(ptr) {
            Ok(_) => {
                self.offset += UREF_SIZE;
                Ok((ptr, UREF_SIZE))
            }

            Err(e) => Err(e),
        }
    }
}

fn mock_account(addr: [u8; 20]) -> (Key, value::Account) {
    let account = value::Account::new([0u8; 32], 0, BTreeMap::new());
    let key = Key::Account(addr);

    (key, account)
}

fn mock_gs(init_key: Key, init_account: &value::Account) -> InMemGS {
    let mut result = InMemGS::new();
    let transform = Transform::Write(value::Value::Acct(init_account.clone()));

    let mut m = HashMap::new();
    m.insert(init_key, transform);
    result
        .commit(m)
        .expect("Creation of mocked account should be a success.");

    result
}

fn mock_context<'a>(
    uref_lookup: &'a mut BTreeMap<String, Key>,
    account: &'a value::Account,
    base_key: Key,
) -> RuntimeContext<'a> {
    RuntimeContext::new(uref_lookup, account, base_key)
}

fn mock_uref_lookup() -> BTreeMap<String, Key> {
    BTreeMap::new()
}

fn mock_module() -> Module {
    module().build()
}

fn gs_write<'a, 'b, R: DbReader>(
    runtime: &mut Runtime<'a, 'b, R>,
    key: (u32, usize),
    value: (u32, usize),
) -> Result<(), wasmi::Trap> {
    runtime.write(key.0, key.1 as u32, value.0, value.1 as u32)
}

#[test]
fn valid_uref() {
    let addr = [0u8; 20];
    let (key, account) = mock_account(addr);
    let gs = mock_gs(key, &account);
    let mut env = MockEnv::new(key, account, 0, &gs);
    let mut memory = env.memory_manager();
    let mut runtime = env.runtime();

    //create a valid uref in wasm memory via new_uref
    let uref = memory
        .new_uref(&mut runtime)
        .expect("call to new_uref should succeed");

    //write arbitrary value to wasm memory to allow call to write
    let value = memory
        .write(value::Value::Int32(42))
        .expect("writing value to wasm memory should succeed");

    //Use uref as the key to perform an action on the global state.
    //This should succeed because the uref is valid.
    let _ = gs_write(&mut runtime, uref, value).expect("writing using valid uref should succeed");
}

#[test]
fn forged_uref() {
    let addr = [0u8; 20];
    let (key, account) = mock_account(addr);
    let gs = mock_gs(key, &account);
    let mut env = MockEnv::new(key, account, 0, &gs);
    let mut memory = env.memory_manager();
    let mut runtime = env.runtime();

    //create a forged uref
    let uref = memory
        .write(Key::URef([231u8; 32]))
        .expect("writing key to wasm memory should succeed");

    //write arbitrary value to wasm memory to allow call to write
    let value = memory
        .write(value::Value::Int32(42))
        .expect("writing value to wasm memory should succeed");

    //Use uref as the key to perform an action on the global state.
    //This should fail because the uref was forged
    let trap = gs_write(&mut runtime, uref, value).expect_err("use of forged key should fail");

    assert_eq!(format!("{:?}", trap).contains("ForgedReference"), true);
}
