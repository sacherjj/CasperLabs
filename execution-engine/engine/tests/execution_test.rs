extern crate common;
extern crate execution_engine;
extern crate parity_wasm;
extern crate shared;
extern crate storage;
extern crate wasm_prep;
extern crate wasmi;

use common::bytesrepr::ToBytes;
use common::key::{AccessRights, Key, UREF_SIZE};
use common::value::{self, Value};
use execution_engine::execution::{Runtime, RuntimeContext};
use execution_engine::trackingcopy::TrackingCopy;
use parity_wasm::builder::module;
use parity_wasm::elements::Module;
use shared::newtypes::Blake2bHash;
use std::cell::RefCell;
use std::collections::{BTreeMap, HashMap};
use std::rc::Rc;
use storage::gs::{inmem::*, DbReader};
use storage::history::*;
use storage::transform::Transform;
use wasm_prep::MAX_MEM_PAGES;
use wasmi::memory_units::Pages;
use wasmi::{MemoryInstance, MemoryRef};

struct MockEnv {
    key: Key,
    account: value::Account,
    uref_lookup: BTreeMap<String, Key>,
    gas_limit: u64,
    memory: MemoryRef,
}

impl MockEnv {
    pub fn new(key: Key, account: value::Account, gas_limit: u64) -> Self {
        let uref_lookup = mock_uref_lookup();
        let memory = MemoryInstance::alloc(Pages(17), Some(Pages(MAX_MEM_PAGES as usize)))
            .expect("Mocked memory should be able to be created.");

        MockEnv {
            key,
            account,
            uref_lookup,
            gas_limit,
            memory,
        }
    }

    pub fn runtime<'a>(
        &'a mut self,
        tc: &'a mut TrackingCopy<InMemGS<Key, Value>>,
        address: [u8; 20],
        timestamp: u64,
        nonce: u64,
        module: Module,
    ) -> Runtime<'a, InMemGS<Key, Value>> {
        let context = mock_context(&mut self.uref_lookup, &self.account, self.key);
        Runtime::new(
            self.memory.clone(),
            tc,
            module,
            self.gas_limit,
            address,
            nonce,
            timestamp,
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
        self.write_raw(t.to_bytes())
    }

    pub fn write_raw(&mut self, bytes: Vec<u8>) -> Result<(u32, usize), wasmi::Error> {
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

    pub fn read_raw(&self, offset: u32, target: &mut [u8]) -> Result<(), wasmi::Error> {
        self.memory.get_into(offset, target)
    }

    pub fn new_uref<'a, R: DbReader>(
        &mut self,
        runtime: &mut Runtime<'a, R>,
    ) -> Result<(u32, usize), wasmi::Trap>
    where
        R::Error: Into<execution_engine::execution::Error>,
    {
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

fn mock_tc(init_key: Key, init_account: &value::Account) -> TrackingCopy<InMemGS<Key, Value>> {
    let root_hash: Blake2bHash = [0u8; 32].into();
    let mut hist = InMemHist::new(&root_hash);
    let transform = Transform::Write(value::Value::Account(init_account.clone()));

    let mut m = HashMap::new();
    m.insert(init_key, transform);
    hist.commit(root_hash, m)
        .expect("Creation of mocked account should be a success.");

    let reader = hist
        .checkout(root_hash)
        .expect("Checkout should not throw errors.")
        .expect("Root hash should exist.");

    TrackingCopy::new(reader)
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

fn gs_write<'a, R: DbReader>(
    runtime: &mut Runtime<'a, R>,
    key: (u32, usize),
    value: (u32, usize),
) -> Result<(), wasmi::Trap>
where
    R::Error: Into<execution_engine::execution::Error>,
{
    runtime.write(key.0, key.1 as u32, value.0, value.1 as u32)
}

#[test]
fn valid_uref() {
    let addr = [0u8; 20];
    let timestamp: u64 = 1000;
    let nonce: u64 = 1;
    let (key, account) = mock_account(addr);
    let tc = Rc::new(RefCell::new(mock_tc(key, &account)));
    let mut env = MockEnv::new(key, account, 0);
    let mut memory = env.memory_manager();
    let mut tc_borrowed = tc.borrow_mut();
    let mut runtime = env.runtime(&mut tc_borrowed, addr, timestamp, nonce, mock_module());

    // create a valid uref in wasm memory via new_uref
    let uref = memory
        .new_uref(&mut runtime)
        .expect("call to new_uref should succeed");

    // write arbitrary value to wasm memory to allow call to write
    let value = memory
        .write(value::Value::Int32(42))
        .expect("writing value to wasm memory should succeed");

    // Use uref as the key to perform an action on the global state.
    // This should succeed because the uref is valid.
    gs_write(&mut runtime, uref, value).expect("writing using valid uref should succeed");
}

#[test]
fn forged_uref() {
    let addr = [0u8; 20];
    let timestamp: u64 = 1000;
    let nonce: u64 = 1;
    let (key, account) = mock_account(addr);
    let tc = Rc::new(RefCell::new(mock_tc(key, &account)));
    let mut env = MockEnv::new(key, account, 0);
    let mut memory = env.memory_manager();
    let mut tc_borrowed = tc.borrow_mut();
    let mut runtime = env.runtime(&mut tc_borrowed, addr, timestamp, nonce, mock_module());

    // create a forged uref
    let uref = memory
        .write(Key::URef([231u8; 32], AccessRights::ReadWrite))
        .expect("writing key to wasm memory should succeed");

    // write arbitrary value to wasm memory to allow call to write
    let value = memory
        .write(value::Value::Int32(42))
        .expect("writing value to wasm memory should succeed");

    // Use uref as the key to perform an action on the global state.
    // This should fail because the uref was forged
    let trap = gs_write(&mut runtime, uref, value).expect_err("use of forged key should fail");

    assert_eq!(format!("{:?}", trap).contains("ForgedReference"), true);
}
