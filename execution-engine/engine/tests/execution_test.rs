extern crate common;
extern crate execution_engine;
extern crate parity_wasm;
extern crate shared;
extern crate storage;
extern crate wabt;
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
use std::collections::{BTreeMap, HashMap, HashSet};
use std::rc::Rc;
use std::iter::once;
use storage::gs::{inmem::*, DbReader};
use storage::history::*;
use storage::transform::Transform;
use wasm_prep::MAX_MEM_PAGES;
use wasmi::memory_units::Pages;
use wasmi::{MemoryInstance, MemoryRef};

struct MockEnv {
    pub key: Key,
    pub account: value::Account,
    pub uref_lookup: BTreeMap<String, Key>,
    pub known_urefs: HashSet<Key>,
    pub gas_limit: u64,
    pub memory: MemoryRef,
}

impl MockEnv {
    pub fn new(
        key: Key,
        uref_lookup: BTreeMap<String, Key>,
        known_urefs: HashSet<Key>,
        account: value::Account,
        gas_limit: u64,
    ) -> Self {
        let memory = MemoryInstance::alloc(Pages(17), Some(Pages(MAX_MEM_PAGES as usize)))
            .expect("Mocked memory should be able to be created.");

        MockEnv {
            key,
            account,
            uref_lookup,
            known_urefs,
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
        let context = mock_context(&mut self.uref_lookup, &mut self.known_urefs, &self.account, self.key);
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

struct StoreContractResult {
    contract_ptr: u32,
    contract_len: usize,
    urefs_ptr: u32,
    urefs_len: usize,
    hash_ptr: u32,
}

impl WasmMemoryManager {
    pub fn new(memory: MemoryRef) -> Self {
        WasmMemoryManager { memory, offset: 0 }
    }

    /// Writes necessary data to Wasm memory so that host can read it.
    /// Returns pointers and lengths of respective pieces of data to pass to ffi call.
    pub fn store_contract(
        &mut self,
        name: &str,
        known_urefs: BTreeMap<String, Key>,
    ) -> StoreContractResult {
        let (contract_ptr, contract_len) = self
            .write(name.to_owned())
            .expect("Writing contract to wasm memory should work");

        let (urefs_ptr, urefs_len) = self
            .write(known_urefs)
            .expect("Writing urefs to wasm memory should work.");

        let (hash_ptr, _) = self
            .write_raw([0u8; 32].to_vec())
            .expect("Allocating place for hash should work");

        StoreContractResult {
            contract_ptr,
            contract_len,
            urefs_ptr,
            urefs_len,
            hash_ptr,
        }
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
    known_urefs: &'a mut HashSet<Key>,
    account: &'a value::Account,
    base_key: Key,
) -> RuntimeContext<'a> {
    let mut context = RuntimeContext::new(uref_lookup, account, base_key);
    known_urefs.iter().for_each(|key| context.insert_uref(key.clone()));
    context
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

struct TestFixture {
    addr: [u8; 20],
    timestamp: u64,
    nonce: u64,
    env: MockEnv,
    memory: WasmMemoryManager,
    tc: Rc<RefCell<TrackingCopy<InMemGS<Key, Value>>>>,
}

impl TestFixture {
    fn new(
        addr: [u8; 20],
        timestamp: u64,
        nonce: u64,
        env: MockEnv,
        memory: WasmMemoryManager,
        tc: Rc<RefCell<TrackingCopy<InMemGS<Key, Value>>>>,
    ) -> TestFixture {
        TestFixture {
            addr,
            timestamp,
            nonce,
            env,
            memory,
            tc,
        }
    }
}

impl Default for TestFixture {
    fn default() -> Self {
        let addr = [0u8; 20];
        let timestamp: u64 = 1000;
        let nonce: u64 = 1;
        let (key, account) = mock_account(addr);
        let tc = Rc::new(RefCell::new(mock_tc(key, &account)));
        let uref_lookup: BTreeMap<String, Key> = BTreeMap::new();
        let known_urefs: HashSet<Key> = HashSet::new();
        let env = MockEnv::new(key, uref_lookup, known_urefs, account, 0);
        let memory = env.memory_manager();
        TestFixture::new(addr, timestamp, nonce, env, memory, tc)
    }
}

#[test]
fn valid_uref() {
    // Test fixture
    let mut test_fixture: TestFixture = Default::default();
    let mut tc_borrowed = test_fixture.tc.borrow_mut();
    let mut runtime = test_fixture.env.runtime(
        &mut tc_borrowed,
        test_fixture.addr,
        test_fixture.timestamp,
        test_fixture.nonce,
        mock_module(),
    );

    // create a valid uref in wasm memory via new_uref
    let uref = test_fixture
        .memory
        .new_uref(&mut runtime)
        .expect("call to new_uref should succeed");

    // write arbitrary value to wasm memory to allow call to write
    let value = test_fixture
        .memory
        .write(value::Value::Int32(42))
        .expect("writing value to wasm memory should succeed");

    // Use uref as the key to perform an action on the global state.
    // This should succeed because the uref is valid.
    gs_write(&mut runtime, uref, value).expect("writing using valid uref should succeed");
}

#[test]
fn forged_uref() {
    // Test fixture
    let mut test_fixture: TestFixture = Default::default();
    let mut tc_borrowed = test_fixture.tc.borrow_mut();
    let mut runtime = test_fixture.env.runtime(
        &mut tc_borrowed,
        test_fixture.addr,
        test_fixture.timestamp,
        test_fixture.nonce,
        mock_module(),
    );

    // create a forged uref
    let uref = test_fixture
        .memory
        .write(Key::URef([231u8; 32], AccessRights::ReadWrite))
        .expect("writing key to wasm memory should succeed");

    // write arbitrary value to wasm memory to allow call to write
    let value = test_fixture
        .memory
        .write(value::Value::Int32(42))
        .expect("writing value to wasm memory should succeed");

    // Use uref as the key to perform an action on the global state.
    // This should fail because the uref was forged
    let trap = gs_write(&mut runtime, uref, value).expect_err("use of forged key should fail");

    assert_eq!(format!("{:?}", trap).contains("ForgedReference"), true);
}

use execution_engine::execution::rename_export_to_call;

// Transforms Wasm module and URef map into Contract.
//
// Renames "name" function to "call" in the passed Wasm module.
// This is necessary because host runtime will do the same thing prior to saving it.
fn contract_bytes_from_wat(
    mut module: Module,
    name: String,
    urefs: BTreeMap<String, Key>,
) -> common::value::Contract {
    rename_export_to_call(&mut module, name);
    let contract_bytes = parity_wasm::serialize(module).expect("Failed to serialize Wasm module.");
    common::value::Contract::new(contract_bytes, urefs)
}

fn read_contract_hash(wasm_memory: &WasmMemoryManager, hash_ptr: u32) -> Key {
    let mut target = [0u8; 32];
    wasm_memory
        .read_raw(hash_ptr, &mut target)
        .expect("Reading hash from raw memory should succed");
    Key::Hash(target)
}

struct TestModule {
    module: Module,
    func_name: String,
}

// Creates a test Wasm module with sample `add` function.
fn create_wasm_module() -> TestModule {
    let wat = r#"
            (module
                (func (export "add") (param i32 i32) (result i32)
                    get_local 0
                    get_local 1
                    i32.add
                )
            )
            "#;

    let wasm_module: Module = {
        let wasm_binary = wabt::wat2wasm(wat).expect("failed to parse wat");
        parity_wasm::deserialize_buffer(&wasm_binary)
            .expect("Failed to deserialize bytes to Wasm module.")
    };
    TestModule {
        module: wasm_module,
        func_name: "add".to_owned(),
    }
}

#[test]
fn store_contract_hash() {
    // Test fixtures
    let mut test_fixture: TestFixture = Default::default();
    let wasm_module = create_wasm_module();
    let urefs: BTreeMap<String, Key> =
        std::iter::once(("SomeKey".to_owned(), Key::Hash([1u8; 32]))).collect();

    let contract = Value::Contract(contract_bytes_from_wat(
        wasm_module.module.clone(),
        "add".to_owned(),
        urefs.clone(),
    ));

    // We need this braces so that the `tc_borrowed` gets dropped
    // and we can borrow it again when we call `effect()`.
    let hash = {
        let mut tc_borrowed = test_fixture.tc.borrow_mut();
        let mut runtime = test_fixture.env.runtime(
            &mut tc_borrowed,
            test_fixture.addr,
            test_fixture.timestamp,
            test_fixture.nonce,
            wasm_module.module,
        );

        let store_result = test_fixture
            .memory
            .store_contract(&wasm_module.func_name, urefs);

        // This is the FFI call that Wasm triggers when it stores a contract in GS.
        runtime
            .store_function(
                store_result.contract_ptr,
                store_result.contract_len as u32,
                store_result.urefs_ptr,
                store_result.urefs_len as u32,
                store_result.hash_ptr,
            )
            .expect("store_function should succeed");

        read_contract_hash(&test_fixture.memory, store_result.hash_ptr)
    };

    // Test that Runtime stored contract under expected hash
    let transforms = test_fixture.tc.borrow().effect().1;
    let effect = transforms.get(&hash).unwrap();
    // Assert contract in the GlobalState is the one we wanted to store.
    assert_eq!(effect, &Transform::Write(contract));
}

#[test]
fn store_contract_hash_illegal_urefs() {
    // Test fixtures
    let mut test_fixture: TestFixture = Default::default();
    let wasm_module = create_wasm_module();
    // Create URef we don't own
    let uref = Key::URef([1u8; 32], AccessRights::Read);
    let urefs: BTreeMap<String, Key> = std::iter::once(("ForgedURef".to_owned(), uref)).collect();

    let mut tc_borrowed = test_fixture.tc.borrow_mut();
    let mut runtime = test_fixture.env.runtime(
        &mut tc_borrowed,
        test_fixture.addr,
        test_fixture.timestamp,
        test_fixture.nonce,
        wasm_module.module,
    );

    let store_result = test_fixture
        .memory
        .store_contract(&wasm_module.func_name, urefs);

    // This is the FFI call that Wasm triggers when it stores a contract in GS.
    let result = runtime.store_function(
        store_result.contract_ptr,
        store_result.contract_len as u32,
        store_result.urefs_ptr,
        store_result.urefs_len as u32,
        store_result.hash_ptr,
    );

    // Since we don't know the urefs we wanted to store together with the contract
    // Runtime will panic with ForgedReference exception.
    match result {
        Err(error) => assert!(format!("{:?}", error).contains("ForgedReference")),
        Ok(_) => panic!("Error. Test should fail."),
    }
}

#[test]
fn store_contract_hash_legal_urefs() {
    // Test fixtures
    let mut test_fixture: TestFixture = Default::default();
    let wasm_module = create_wasm_module();
    // We need this braces so that the `tc_borrowed` gets dropped
    // and we can borrow it again when we call `effect()`.
    let (hash, contract) = {
        let mut tc_borrowed = test_fixture.tc.borrow_mut();
        let mut runtime = test_fixture.env.runtime(
            &mut tc_borrowed,
            test_fixture.addr,
            test_fixture.timestamp,
            test_fixture.nonce,
            wasm_module.module.clone(),
        );

        let uref = {
            // We are generating new URef the "correct" way.
            // It asks a host to generate a URef which puts it into
            // `known_urefs` map of the current runtime context.
            // Thanks to that, subsequent uses of this URef are valid
            // because they "belong" to the context that uses them.
            let (uref_ptr, _) = test_fixture
                .memory
                .new_uref(&mut runtime)
                .expect("URef generation failed");
            let mut tmp = [1u8; UREF_SIZE];
            test_fixture
                .memory
                .read_raw(uref_ptr, &mut tmp)
                .expect("Reading URef from wasm memory should work.");
            let key: Key =
                common::bytesrepr::deserialize(&tmp).expect("URef deserialization should work.");
            key
        };

        let urefs: BTreeMap<String, Key> = {
            let mut tmp = BTreeMap::new();
            tmp.insert("KnownURef".to_owned(), uref);
            tmp.insert("PublicHash".to_owned(), Key::Hash([1u8; 32]));
            tmp
        };

        let contract = Value::Contract(contract_bytes_from_wat(
            wasm_module.module.clone(),
            "add".to_owned(),
            urefs.clone(),
        ));

        let store_result = test_fixture
            .memory
            .store_contract(&wasm_module.func_name, urefs);

        // This is the FFI call that Wasm triggers when it stores a contract in GS.
        runtime
            .store_function(
                store_result.contract_ptr,
                store_result.contract_len as u32,
                store_result.urefs_ptr,
                store_result.urefs_len as u32,
                store_result.hash_ptr,
            )
            .expect("store_function should succeed");

        (
            read_contract_hash(&test_fixture.memory, store_result.hash_ptr),
            contract,
        )
    };

    // Test that Runtime stored contract under expected hash
    let transforms = test_fixture.tc.borrow().effect().1;
    let effect = transforms.get(&hash).unwrap();
    // Assert contract in the GlobalState is the one we wanted to store.
    assert_eq!(effect, &Transform::Write(contract));
}
