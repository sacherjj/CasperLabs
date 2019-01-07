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
use std::collections::BTreeMap;
use storage::transform::Transform;
use storage::{GlobalState, InMemGS, InMemTC};
use wasm_prep::MAX_MEM_PAGES;
use wasmi::memory_units::Pages;
use wasmi::{MemoryInstance, MemoryRef};

#[allow(dead_code)]
struct MockEnv {
    addr: [u8; 20],
    key: Key,
    account: value::Account,
    uref_lookup: BTreeMap<String, Key>,
    gs: InMemGS,
    tc: InMemTC,
    gas_limit: u64,
    memory: MemoryRef,
}

impl MockEnv {
    pub fn new(addr: [u8; 20], gas_limit: u64) -> Self {
        let (key, account) = mock_account(addr);
        let gs = mock_gs(key, &account);
        let tc = gs.tracking_copy();
        let uref_lookup = mock_uref_lookup();
        let memory = MemoryInstance::alloc(Pages(17), Some(Pages(MAX_MEM_PAGES as usize)))
            .expect("Mocked memory should be able to be created.");

        MockEnv {
            addr,
            key,
            account,
            uref_lookup,
            gs,
            tc,
            gas_limit,
            memory,
        }
    }

    pub fn runtime<'a>(&'a mut self) -> Runtime<'a, InMemTC> {
        let context = mock_context(&mut self.uref_lookup, &self.account, self.key);
        Runtime::new(
            self.memory.clone(),
            &mut self.tc,
            mock_module(),
            &self.gas_limit,
            context,
        )
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

    result
        .apply(init_key, transform)
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

#[test]
fn valid_uref() {
    let mut env = MockEnv::new([0u8; 20], 0);
    let memory = env.memory.clone();
    let mut runtime = env.runtime();

    let uref_size = UREF_SIZE as u32;
    let uref_ptr: u32 = 0;
    let value_ptr = uref_ptr + uref_size + 1;
    let value = value::Value::Int32(42).to_bytes();
    let _ = runtime
        .new_uref(uref_ptr)
        .expect("call to new_uref should succeed");
    let _ = memory
        .set(value_ptr, &value)
        .expect("writing value to wasm memory should succeed");
    let _ = runtime
        .write(uref_ptr, uref_size, value_ptr, value.len() as u32)
        .expect("writing using valid uref should succeed");
}

#[test]
fn forged_uref() {
    let mut env = MockEnv::new([0u8; 20], 0);
    let memory = env.memory.clone();
    let mut runtime = env.runtime();

    let uref_ptr: u32 = 0;
    let uref = Key::URef([231u8; 32]).to_bytes();
    let uref_size = uref.len() as u32;
    let value_ptr = uref_ptr + uref_size + 1;
    let value = value::Value::Int32(42).to_bytes();
    let _ = memory
        .set(uref_ptr, &uref)
        .expect("writing uref to wasm memory should succeed");
    let _ = memory
        .set(value_ptr, &value)
        .expect("writing value to wasm memory should succeed");
    let trap = runtime
        .write(uref_ptr, uref_size, value_ptr, value.len() as u32)
        .expect_err("use of forged key should fail");

    assert_eq!(format!("{:?}", trap).contains("ForgedReference"), true);
}
