extern crate common;
extern crate execution_engine;
extern crate failure;
extern crate parity_wasm;
extern crate rand;
extern crate rand_chacha;
extern crate shared;
extern crate storage;
extern crate wabt;
extern crate wasm_prep;
extern crate wasmi;

use common::bytesrepr::{deserialize, FromBytes, ToBytes};
use common::key::{AccessRights, Key, UREF_SIZE};
use common::value::{self, Account, Contract, Value};
use execution_engine::execution::Runtime;
use execution_engine::runtime_context::RuntimeContext;
use execution_engine::trackingcopy::TrackingCopy;
use failure::Error;
use parity_wasm::builder::module;
use parity_wasm::elements::Module;
use rand::RngCore;
use shared::newtypes::Blake2bHash;
use std::cell::RefCell;
use std::collections::{BTreeMap, HashMap, HashSet};
use std::iter::once;
use std::iter::IntoIterator;
use std::rc::Rc;
use storage::global_state::{inmem::*, StateReader};
use storage::history::*;
use storage::transform::Transform;
use wasm_prep::MAX_MEM_PAGES;
use wasmi::memory_units::Pages;
use wasmi::{MemoryInstance, MemoryRef};

struct MockEnv {
    pub base_key: Key,
    pub deploy_account: Account,
    pub uref_lookup: BTreeMap<String, Key>,
    pub known_urefs: HashSet<Key>,
    pub gas_limit: u64,
    pub memory: MemoryRef,
}

impl MockEnv {
    pub fn new(
        base_key: Key,
        uref_lookup: BTreeMap<String, Key>,
        known_urefs: HashSet<Key>,
        deploy_account: Account,
        gas_limit: u64,
    ) -> Self {
        let memory = MemoryInstance::alloc(Pages(17), Some(Pages(MAX_MEM_PAGES as usize)))
            .expect("Mocked memory should be able to be created.");

        MockEnv {
            base_key,
            deploy_account,
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
        let context = mock_context(
            &mut self.uref_lookup,
            &mut self.known_urefs,
            &self.deploy_account,
            self.base_key,
        );
        Runtime::new(
            Vec::new(),
            self.memory.clone(),
            tc,
            module,
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
        let (contract_ptr, contract_len) = self.write(name.to_owned());

        let (urefs_ptr, urefs_len) = self.write(known_urefs);

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

    pub fn write<T: ToBytes>(&mut self, t: T) -> (u32, usize) {
        self.write_raw(t.to_bytes().expect("ToBytes conversion should work."))
            .expect("Writing to Wasm memory should work.")
    }

    pub fn write_raw(&mut self, bytes: Vec<u8>) -> Result<(u32, usize), Error> {
        let ptr = self.offset as u32;

        match self.memory.set(ptr, &bytes) {
            Ok(_) => {
                let len = bytes.len();
                self.offset += len;
                Ok((ptr, len))
            }

            Err(e) => Err(e.into()),
        }
    }

    pub fn alloc(&mut self, len: usize) -> u32 {
        let ptr = self.offset as u32;
        self.offset += len;
        ptr
    }

    pub fn read_raw(&self, offset: u32, target: &mut [u8]) -> Result<(), wasmi::Error> {
        self.memory.get_into(offset, target)
    }

    pub fn read_bytes(&self, offset: u32, len: usize) -> Result<Vec<u8>, wasmi::Error> {
        self.memory.get(offset, len)
    }

    pub fn new_uref<'a, R: StateReader<Key, Value>>(
        &mut self,
        runtime: &mut Runtime<'a, R>,
        value: (u32, usize), //pointer, length tuple
    ) -> Result<(u32, usize), wasmi::Trap>
    where
        R::Error: Into<execution_engine::execution::Error>,
    {
        let ptr = self.offset as u32;
        let (value_ptr, value_size) = value;

        match runtime.new_uref(ptr, value_ptr, value_size as u32) {
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
    let gas_limit = 1000u64;
    let mut context =
        RuntimeContext::new(uref_lookup, HashMap::new(), account, base_key, gas_limit);
    known_urefs
        .iter()
        .for_each(|key| context.insert_uref(key.clone()));
    context
}

fn mock_module() -> Module {
    module().build()
}

// Create random account Key.
fn random_account_key<G: RngCore>(entropy_source: &mut G) -> Key {
    let mut key = [0u8; 20];
    entropy_source.fill_bytes(&mut key);
    Key::Account(key)
}

// Create random contract Key.
fn random_contract_key<G: RngCore>(entropy_source: &mut G) -> Key {
    let mut key = [0u8; 32];
    entropy_source.fill_bytes(&mut key);
    Key::Hash(key)
}

// Create random URef Key.
fn random_uref_key<G: RngCore>(entropy_source: &mut G, rights: AccessRights) -> Key {
    let mut key = [0u8; 32];
    entropy_source.fill_bytes(&mut key);
    Key::URef(key, rights)
}

fn gs_write<'a, R: StateReader<Key, Value>>(
    runtime: &mut Runtime<'a, R>,
    key: (u32, usize),
    value: (u32, usize),
) -> Result<(), wasmi::Trap>
where
    R::Error: Into<execution_engine::execution::Error>,
{
    runtime.write(key.0, key.1 as u32, value.0, value.1 as u32)
}

/// Reads data from the GlobalState that lives under a key
/// that can be found in the Wasm memory under `key` (pointer, length) tuple.
fn gs_read<'a, R: StateReader<Key, Value>, T: FromBytes>(
    memory: &mut WasmMemoryManager,
    runtime: &mut Runtime<'a, R>,
    key: (u32, usize),
) -> Result<T, wasmi::Trap>
where
    R::Error: Into<execution_engine::execution::Error>,
{
    let size = runtime.read_value(key.0, key.1 as u32)?;
    // prepare enough bytes in the Wasm memory
    let value_ptr = memory.alloc(size);
    // Ask runtime to write to prepared space in the Wasm memory
    runtime
        .set_mem_from_buf(value_ptr)
        .expect("Writing value to WasmMemory should succeed.");
    // Read value from the Wasm memory
    let bytes = memory
        .read_bytes(value_ptr, size)
        .expect("Reading from WasmMemory should work");
    Ok(deserialize(&bytes).expect("Deserializing should work"))
}

// Helper method that writes `value` to `wasm_memory`
fn wasm_write<T: ToBytes>(wasm_memory: &mut WasmMemoryManager, value: T) -> (u32, usize) {
    wasm_memory.write(value)
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

    // write arbitrary value to wasm memory to allow call to write
    let init_value = wasm_write(&mut test_fixture.memory, value::Value::Int32(42));

    let new_value = wasm_write(&mut test_fixture.memory, value::Value::Int32(43));

    // create a valid uref in wasm memory via new_uref
    let uref = test_fixture
        .memory
        .new_uref(&mut runtime, init_value)
        .expect("call to new_uref should succeed");

    // Use uref as the key to perform an action on the global state.
    // This should succeed because the uref is valid.
    gs_write(&mut runtime, uref, new_value).expect("writing using valid uref should succeed");
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

    let mut rng = rand::thread_rng();
    // create a forged uref
    let uref = wasm_write(
        &mut test_fixture.memory,
        random_uref_key(&mut rng, AccessRights::ReadWrite),
    );

    // write arbitrary value to wasm memory to allow call to write
    let value = wasm_write(&mut test_fixture.memory, value::Value::Int32(42));

    // Use uref as the key to perform an action on the global state.
    // This should fail because the uref was forged
    let trap = gs_write(&mut runtime, uref, value);

    assert_forged_reference(trap);
}

use execution_engine::execution::rename_export_to_call;

// Transforms Wasm module and URef map into Contract.
//
// Renames "name" function to "call" in the passed Wasm module.
// This is necessary because host runtime will do the same thing prior to saving it.
fn contract_bytes_from_wat(mut test_module: TestModule, urefs: BTreeMap<String, Key>) -> Value {
    rename_export_to_call(&mut test_module.module, test_module.func_name);
    let contract_bytes =
        parity_wasm::serialize(test_module.module).expect("Failed to serialize Wasm module.");
    Value::Contract(common::value::Contract::new(contract_bytes, urefs))
}

fn read_contract_hash(wasm_memory: &WasmMemoryManager, hash_ptr: u32) -> Key {
    let mut target = [0u8; 32];
    wasm_memory
        .read_raw(hash_ptr, &mut target)
        .expect("Reading hash from raw memory should succed");
    Key::Hash(target)
}

#[derive(Clone)]
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

fn urefs_map<I: IntoIterator<Item = (String, Key)>>(input: I) -> BTreeMap<String, Key> {
    input.into_iter().collect()
}

#[test]
fn store_contract_hash() {
    // Tests that storing contracts (functions) works.
    // Test fixtures
    let mut test_fixture: TestFixture = Default::default();
    let mut rng = rand::thread_rng();
    let hash = random_contract_key(&mut rng);
    let wasm_module = create_wasm_module();
    let urefs = urefs_map(vec![("SomeKey".to_owned(), hash)]);

    let contract = contract_bytes_from_wat(wasm_module.clone(), urefs.clone());

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

fn assert_invalid_access<T>(result: Result<T, wasmi::Trap>) {
    assert_error_contains(result, "InvalidAccess")
}

fn assert_forged_reference<T>(result: Result<T, wasmi::Trap>) {
    assert_error_contains(result, "ForgedReference")
}

fn assert_error_contains<T>(result: Result<T, wasmi::Trap>, msg: &str) {
    match result {
        Err(error) => assert!(format!("{:?}", error).contains(msg)),
        Ok(_) => panic!("Error. Test should fail but it didn't."),
    }
}

#[test]
fn store_contract_hash_illegal_urefs() {
    // Tests that storing function (contract) with illegal (unknown) urefs is an error.
    // Test fixtures
    let mut test_fixture: TestFixture = Default::default();
    let mut rng = rand::thread_rng();
    let wasm_module = create_wasm_module();
    // Create URef we don't own
    let uref = random_uref_key(&mut rng, AccessRights::Read);
    let urefs = urefs_map(vec![("ForgedURef".to_owned(), uref)]);

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
    assert_forged_reference(result);
}

#[test]
fn store_contract_hash_legal_urefs() {
    // Tests that storing function (contract) with valid (known) uref works.
    // Test fixtures
    let mut test_fixture: TestFixture = Default::default();
    let mut rng = rand::thread_rng();
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

        // Initial value of the uref the in the contract's
        // known_urefs map.
        let init_value = wasm_write(&mut test_fixture.memory, value::Value::Int32(42));

        let uref = {
            // We are generating new URef the "correct" way.
            // It asks a host to generate a URef which puts it into
            // `known_urefs` map of the current runtime context.
            // Thanks to that, subsequent uses of this URef are valid
            // because they "belong" to the context that uses them.
            let (uref_ptr, _) = test_fixture
                .memory
                .new_uref(&mut runtime, init_value)
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

        let urefs = urefs_map(vec![
            ("KnownURef".to_owned(), uref),
            ("PublicHash".to_owned(), random_contract_key(&mut rng)),
        ]);

        let contract = contract_bytes_from_wat(wasm_module.clone(), urefs.clone());

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

#[test]
fn store_contract_uref_known_key() {
    // Tests that storing function (contract) under known and writeable uref,
    // with known refs, works.
    // ---- Test fixtures ----
    let mut rng = rand::thread_rng();
    // URef where we will write contract
    let contract_uref = random_uref_key(&mut rng, AccessRights::ReadWrite);
    // URef we want to store WITH the contract so that it can use it later
    let known_uref = random_uref_key(&mut rng, AccessRights::ReadWrite);
    let urefs = urefs_map(vec![("KnownURef".to_owned(), known_uref)]);
    let known_urefs: HashSet<Key> = once(contract_uref).chain(once(known_uref)).collect();
    let mut test_fixture: TestFixture = {
        let addr = [0u8; 20];
        let timestamp = 1u64;
        let nonce = 1u64;
        let (key, account) = mock_account(addr);
        let tc = Rc::new(RefCell::new(mock_tc(key, &account)));
        let env = MockEnv::new(key, urefs.clone(), known_urefs, account, 0);
        let memory = env.memory_manager();
        TestFixture::new(addr, timestamp, nonce, env, memory, tc)
    };

    let wasm_module = create_wasm_module();
    // ---- Test fixture ----

    // We need this braces so that the `tc_borrowed` gets dropped
    // and we can borrow it again when we call `effect()`.
    let contract = {
        let mut tc_borrowed = test_fixture.tc.borrow_mut();
        let mut runtime = test_fixture.env.runtime(
            &mut tc_borrowed,
            test_fixture.addr,
            test_fixture.timestamp,
            test_fixture.nonce,
            wasm_module.module.clone(),
        );

        let wasm_contract_uref = wasm_write(&mut test_fixture.memory, contract_uref);

        let contract = contract_bytes_from_wat(wasm_module, urefs.clone());

        let wasm_contract = wasm_write(&mut test_fixture.memory, contract.clone());

        // This is the FFI call that Wasm triggers when it stores a contract in GS.
        gs_write(&mut runtime, wasm_contract_uref, wasm_contract).expect("write should succeed");

        contract
    };

    // Test that Runtime stored contract under expected hash
    let transforms = test_fixture.tc.borrow().effect().1;
    let effect = transforms.get(&contract_uref).unwrap();
    // Assert contract in the GlobalState is the one we wanted to store.
    assert_eq!(effect, &Transform::Write(contract));
}

#[test]
fn store_contract_uref_forged_key() {
    // Tests that storing function (contract) under forged but writeable uref fails.
    // ---- Test fixtures ----
    // URef where we will write contract
    let mut rng = rand::thread_rng();
    let forged_contract_uref = random_uref_key(&mut rng, AccessRights::ReadWrite);
    // URef we want to store WITH the contract so that it can use it later
    let known_uref = random_uref_key(&mut rng, AccessRights::ReadWrite);
    let urefs = urefs_map(vec![("KnownURef".to_owned(), known_uref)]);
    let known_urefs: HashSet<Key> = once(known_uref).collect();

    let mut test_fixture: TestFixture = {
        let addr = [0u8; 20];
        let timestamp = 1u64;
        let nonce = 1u64;
        let (key, account) = mock_account(addr);
        let tc = Rc::new(RefCell::new(mock_tc(key, &account)));
        let env = MockEnv::new(key, urefs.clone(), known_urefs, account, 0);
        let memory = env.memory_manager();
        TestFixture::new(addr, timestamp, nonce, env, memory, tc)
    };

    let wasm_module = create_wasm_module();
    // ---- Test fixture ----

    // We need this braces so that the `tc_borrowed` gets dropped
    // and we can borrow it again when we call `effect()`.
    let mut tc_borrowed = test_fixture.tc.borrow_mut();
    let mut runtime = test_fixture.env.runtime(
        &mut tc_borrowed,
        test_fixture.addr,
        test_fixture.timestamp,
        test_fixture.nonce,
        wasm_module.module.clone(),
    );

    let wasm_contract_uref = wasm_write(&mut test_fixture.memory, forged_contract_uref);

    let contract = contract_bytes_from_wat(wasm_module, urefs.clone());

    let wasm_contract = wasm_write(&mut test_fixture.memory, contract.clone());

    // This is the FFI call that Wasm triggers when it stores a contract in GS.
    let result = gs_write(&mut runtime, wasm_contract_uref, wasm_contract);
    assert_forged_reference(result);
}

#[test]
fn account_key_writeable() {
    // Tests that account key is not writeable.
    // Test fixtures
    let mut test_fixture: TestFixture = Default::default();
    let mut rng = rand::thread_rng();
    let wasm_module = create_wasm_module();

    let account_key = random_account_key(&mut rng);
    let wasm_key = wasm_write(&mut test_fixture.memory, account_key);
    let wasm_value = wasm_write(&mut test_fixture.memory, Value::Int32(1));

    let mut tc_borrowed = test_fixture.tc.borrow_mut();
    let mut runtime = test_fixture.env.runtime(
        &mut tc_borrowed,
        test_fixture.addr,
        test_fixture.timestamp,
        test_fixture.nonce,
        wasm_module.module.clone(),
    );

    let result = gs_write(&mut runtime, wasm_key, wasm_value);
    assert_invalid_access(result);
}

// Test that is shared between two following tests for reading Account key.
// It is the case that reading an account key should be possible only from within
// the context of the account - i.e. only in the body of "call" function of the deployment.
// `init_value` is the value being written to the `TestFixture`'s account key.
// `base_key_different` is a flag that decides whether we will be writing/reading to
// an account key that is the same as the one in the current context (valid), or different one (invalid).
fn test_account_key_readable(
    init_value: Value,
    base_key_different: bool,
) -> Result<Value, wasmi::Trap> {
    // Tests that accout key is readable.
    // Test fixtures
    let mut test_fixture: TestFixture = Default::default();
    let wasm_module = create_wasm_module();
    let mut rng = rand::thread_rng();

    let mut tc_borrowed = test_fixture.tc.borrow_mut();
    // We write directly to GlobalState so that we can read it later.
    // We purposefully write under account key which is the same as current context's.
    // This way we will be able to read account key.
    let account_key = if base_key_different {
        random_account_key(&mut rng)
    } else {
        Key::Account(test_fixture.addr)
    };
    tc_borrowed.write(account_key, init_value);
    let mut runtime = test_fixture.env.runtime(
        &mut tc_borrowed,
        test_fixture.addr,
        test_fixture.timestamp,
        test_fixture.nonce,
        wasm_module.module.clone(),
    );
    let wasm_account_key = wasm_write(&mut test_fixture.memory, account_key);

    gs_read(&mut test_fixture.memory, &mut runtime, wasm_account_key)
}

#[test]
fn account_key_readable_valid() {
    let init_value = Value::Int32(1);
    let value_read = test_account_key_readable(init_value.clone(), false)
        .expect("Reading account Key should succeed");
    assert_eq!(value_read, init_value);
}

#[test]
fn account_key_readable_invalid() {
    let init_value = Value::Int32(1);
    let read_result = test_account_key_readable(init_value, true);
    assert_invalid_access(read_result);
}

#[test]
fn account_key_addable_valid() {
    // Adding to an account is valid iff it's being done from within
    // the context of the account. I.e. if account A deploys a contract c1,
    // then during execution of c1 it is allowed to add NamedKeys to A.
    // On the other hand if contract c1, stored previously by A, is being
    // called by some other acccount B then it [c1] cannot add keys to A.

    // Test fixtures
    let mut test_fixture: TestFixture = Default::default();
    let mut rng = rand::thread_rng();
    let wasm_module = create_wasm_module();
    let known_urefs = urefs_map(vec![(
        "PublicHash".to_owned(),
        random_contract_key(&mut rng),
    )]);
    let account = Account::new([1u8; 32], 1, known_urefs.clone());
    let account_key = Key::Account(test_fixture.addr);
    let wasm_account_key = wasm_write(&mut test_fixture.memory, account_key);
    // This is the key we will want to add to an account
    let additional_key = ("PublichHash#2".to_owned(), random_contract_key(&mut rng));
    let named_key = Value::NamedKey(additional_key.0.clone(), additional_key.1);
    let wasm_named_key = wasm_write(&mut test_fixture.memory, named_key.clone());
    {
        let mut tc_borrowed = test_fixture.tc.borrow_mut();
        // Write an account under current context's key
        tc_borrowed.write(
            Key::Account(test_fixture.addr),
            Value::Account(account.clone()),
        );

        let mut runtime = test_fixture.env.runtime(
            &mut tc_borrowed,
            test_fixture.addr,
            test_fixture.timestamp,
            test_fixture.nonce,
            wasm_module.module.clone(),
        );

        // Add key to current context's account.
        runtime
            .add(
                wasm_account_key.0,
                wasm_account_key.1 as u32,
                wasm_named_key.0,
                wasm_named_key.1 as u32,
            )
            .expect("Adding new named key should work");
    }
    let mut tc = test_fixture.tc.borrow_mut();
    let updated_account = {
        let additional_key_map = urefs_map(
            once((additional_key.0.clone(), additional_key.1)).chain(known_urefs.clone()),
        );
        Account::new([1u8; 32], account.nonce(), additional_key_map)
    };
    let tc_account: Value = tc
        .get(&Key::Account(test_fixture.addr))
        .expect("Reading from TrackingCopy should work")
        .unwrap();

    assert_eq!(tc_account, Value::Account(updated_account));
}

#[test]
fn account_key_addable_invalid() {
    // Adding keys to another account should be invalid.
    // See comment in the `account_key_addable_valid` test for more context.

    // Test fixtures
    let mut test_fixture: TestFixture = Default::default();
    let mut rng = rand::thread_rng();
    let wasm_module = create_wasm_module();
    let known_urefs = urefs_map(vec![(
        "PublicHash".to_owned(),
        random_contract_key(&mut rng),
    )]);
    let account = Account::new([1u8; 32], 1, known_urefs.clone());
    // This is the key we will want to add to an account
    let additional_key = ("PublichHash#2".to_owned(), random_contract_key(&mut rng));
    let named_key = Value::NamedKey(additional_key.0, additional_key.1);
    let wasm_named_key = wasm_write(&mut test_fixture.memory, named_key);

    let some_other_account = random_account_key(&mut rng);
    let wasm_other_account = wasm_write(&mut test_fixture.memory, some_other_account);
    // We will try to add keys to an account that is not current context.
    let mut tc_borrowed = test_fixture.tc.borrow_mut();
    // Write an account under current context's key
    tc_borrowed.write(some_other_account, Value::Account(account.clone()));

    let mut runtime = test_fixture.env.runtime(
        &mut tc_borrowed,
        test_fixture.addr,
        test_fixture.timestamp,
        test_fixture.nonce,
        wasm_module.module.clone(),
    );

    // Add key to some account.
    // We cannot use add_uref as in the other test because
    // it would add keys to current context's account.
    // We want to test that adding keys to some OTHER account is not possible.
    let result = runtime.add(
        wasm_other_account.0,
        wasm_other_account.1 as u32,
        wasm_named_key.0,
        wasm_named_key.1 as u32,
    );

    assert_invalid_access(result);
}

#[test]
fn contract_key_writeable() {
    // Tests that contract keys (hashes) are not writeable.
    // Contract can be persisted on the blockchain by the means of `ffi:store_function`.

    let mut test_fixture: TestFixture = Default::default();
    let mut rng = rand::thread_rng();
    let contract_key = random_contract_key(&mut rng);
    let wasm_module = create_wasm_module();

    let urefs = urefs_map(std::iter::empty());

    let contract = contract_bytes_from_wat(wasm_module.clone(), urefs.clone());

    let wasm_key = wasm_write(&mut test_fixture.memory, contract_key);
    let wasm_contract = wasm_write(&mut test_fixture.memory, contract);

    let mut tc_borrowed = test_fixture.tc.borrow_mut();
    let mut runtime = test_fixture.env.runtime(
        &mut tc_borrowed,
        test_fixture.addr,
        test_fixture.timestamp,
        test_fixture.nonce,
        wasm_module.module.clone(),
    );

    let result = gs_write(&mut runtime, wasm_key, wasm_contract);
    assert_invalid_access(result);
}

#[test]
fn contract_key_readable() {
    // Tests that contracts are readable. This test checks that it is possible to execute
    // `call_contract` function which checks whether the key is readable.
    // Test fixtures
    let mut test_fixture: TestFixture = Default::default();
    let mut rng = rand::thread_rng();
    let wasm_module = create_wasm_module();

    let contract_key = random_contract_key(&mut rng);
    let wasm_key = wasm_write(&mut test_fixture.memory, contract_key);
    let empty_vec: Vec<u8> = Vec::new();
    let wasm_args = wasm_write(&mut test_fixture.memory, empty_vec);
    let empty_urefs: Vec<Key> = Vec::new();
    let wasm_urefs = wasm_write(&mut test_fixture.memory, empty_urefs);

    let mut tc_borrowed = test_fixture.tc.borrow_mut();
    let mut runtime = test_fixture.env.runtime(
        &mut tc_borrowed,
        test_fixture.addr,
        test_fixture.timestamp,
        test_fixture.nonce,
        wasm_module.module.clone(),
    );

    let result = runtime.call_contract(
        wasm_key.0,
        wasm_key.1,
        wasm_args.0,
        wasm_args.1,
        wasm_urefs.0,
        wasm_urefs.1,
    );

    // call_contract call in the execution.rs (Runtime) first checks whether key is readable
    // and then fetches value from the memory. In this case it will pass "is_readable" check
    // but will not return results from the GlobalState. This is not perfect test but setting it
    // up in a way where we actually call another contract if very cumbersome.
    match result {
        Err(execution_engine::execution::Error::KeyNotFound(key)) => assert_eq!(key, contract_key),
        Err(error) => panic!("Test failed with unexpected error {:?}", error),
        Ok(_) => panic!("Test should have failed but didn't"),
    }
}

fn test_contract_key_addable(base_key: Key, add_to_key: Key) -> Result<(), wasmi::Trap> {
    let init_contract = Contract::new(Vec::new(), urefs_map(std::iter::empty()));
    // We're setting up the test fixture so that the current context is pointing at `base_key`.
    let mut test_fixture: TestFixture = {
        let addr = [0u8; 20];
        let nonce = 1u64;
        let timestamp = 1u64;
        let gas_limit = 0u64;
        let (acc_key, account) = mock_account(addr);
        let uref_lookup = urefs_map(std::iter::empty());
        let known_urefs: HashSet<Key> = HashSet::new();
        let mut tc = mock_tc(acc_key, &account);
        // Here we create MockEnv with the `base_key` as being an entity under which
        // the contract is executing.
        let env = MockEnv::new(base_key, uref_lookup, known_urefs, account, gas_limit);

        tc.write(base_key, Value::Contract(init_contract.clone()));

        let memory = env.memory_manager();
        TestFixture::new(
            addr,
            timestamp,
            nonce,
            env,
            memory,
            Rc::new(RefCell::new(tc)),
        )
    };
    let wasm_module = create_wasm_module();
    // This is the key we will want to add to a contract
    let wasm_named_key = {
        let mut rng = rand::thread_rng();
        let additional_key = ("PublichHash#2".to_owned(), random_contract_key(&mut rng));
        let named_key = Value::NamedKey(additional_key.0, additional_key.1);
        wasm_write(&mut test_fixture.memory, named_key)
    };

    let wasm_contract_hash = wasm_write(&mut test_fixture.memory, add_to_key);

    let mut tc_borrowed = test_fixture.tc.borrow_mut();

    let mut runtime = test_fixture.env.runtime(
        &mut tc_borrowed,
        test_fixture.addr,
        test_fixture.timestamp,
        test_fixture.nonce,
        wasm_module.module.clone(),
    );

    // We're trying to add to `add_to_key` (which may be different than `base_key`).
    // This way we simulate addition to current (or not) context's base key.
    runtime.add(
        wasm_contract_hash.0,
        wasm_contract_hash.1 as u32,
        wasm_named_key.0,
        wasm_named_key.1 as u32,
    )
}

#[test]
fn contract_key_addable_valid() {
    // Tests that adding to contract key, when it's a base key, is valid.
    let mut rng = rand::thread_rng();
    let contract_key = random_contract_key(&mut rng);
    assert!(test_contract_key_addable(contract_key, contract_key).is_ok());
}

#[test]
fn contract_key_addable_invalid() {
    // Tests that adding to contract key, when it's not a base key, is invalid.
    let mut rng = rand::thread_rng();
    let contract_key = random_contract_key(&mut rng);
    let other_contract_key = random_contract_key(&mut rng);
    let result = test_contract_key_addable(contract_key, other_contract_key);
    assert_invalid_access(result);
}

// Test that is shared between two following tests for reading URef.
// `init_value` is what is being written to the GS at the generated URef as part of test fixture.
// `rights` defines `AccessRights` that will be used when reading URef. It doesn't matter
// when setting up because we are writing directly to the GlobalState so rights are not checked.
fn test_uref_key_readable(init_value: Value, rights: AccessRights) -> Result<Value, wasmi::Trap> {
    let mut rng = rand::thread_rng();
    // URef we will be trying to read.
    let uref = random_uref_key(&mut rng, rights);
    let mut test_fixture: TestFixture = {
        // We need to put `uref`, which we will be using later, to `known_urefs` set
        // of the context's account. Otherwise we will get ForgedReference error.
        let known_urefs: HashSet<Key> = once(uref).collect();
        let empty_uref_map = urefs_map(std::iter::empty());
        let default: TestFixture = Default::default();
        let (key, account) = mock_account(default.addr);
        let env = MockEnv::new(key, empty_uref_map, known_urefs, account.clone(), 0);
        let memory = env.memory_manager();
        let mut init_tc = mock_tc(key, &account);
        // We're putting some data under uref so that we can read it later.
        init_tc.write(uref, init_value.clone());
        let tc = Rc::new(RefCell::new(init_tc));
        TestFixture::new(
            default.addr,
            default.timestamp,
            default.nonce,
            env,
            memory,
            tc,
        )
    };
    let mut tc_borrowed = test_fixture.tc.borrow_mut();
    let mut runtime = test_fixture.env.runtime(
        &mut tc_borrowed,
        test_fixture.addr,
        test_fixture.timestamp,
        test_fixture.nonce,
        mock_module(),
    );
    let wasm_uref = wasm_write(&mut test_fixture.memory, uref);
    gs_read(&mut test_fixture.memory, &mut runtime, wasm_uref)
}

#[test]
fn uref_key_readable_valid() {
    // Tests that URef key is readable when access rights of the key allows for reading.
    let init_value = Value::Int32(1);
    let test_result = test_uref_key_readable(init_value.clone(), AccessRights::Read)
        .expect("Reading from GS should work.");
    assert_eq!(test_result, init_value);
}

#[test]
fn uref_key_readable_invalid() {
    // Tests that reading URef which is not readable fails.
    let init_value = Value::Int32(1);
    let test_result = test_uref_key_readable(init_value.clone(), AccessRights::Add);
    assert_invalid_access(test_result);
}

// Test that is being shared between two following tests for writing to a URef.
// The drill is that we generate URef, add it to the current context (so it can be used by the contract),
// Then we try to write to this URef. Host (Runtime) will validate whether the key we want
// to write to is valid (belongs to the `known_urefs` set) and whether it's writeable.
fn test_uref_key_writeable(rights: AccessRights) -> Result<(), wasmi::Trap> {
    let init_value = Value::Int32(1);
    let mut rng = rand::thread_rng();
    // URef we will be trying to read.
    let uref = random_uref_key(&mut rng, rights);
    let mut test_fixture: TestFixture = {
        // We need to put `uref`, which we will be using later, to `known_urefs` set
        // of the context's account. Otherwise we will get ForgedReference error.
        let known_urefs: HashSet<Key> = once(uref).collect();
        let empty_uref_map = urefs_map(std::iter::empty());
        let default: TestFixture = Default::default();
        let (key, account) = mock_account(default.addr);
        let env = MockEnv::new(key, empty_uref_map, known_urefs, account.clone(), 0);
        let memory = env.memory_manager();
        let mut init_tc = mock_tc(key, &account);
        init_tc.write(uref, init_value.clone());
        let tc = Rc::new(RefCell::new(init_tc));
        TestFixture::new(
            default.addr,
            default.timestamp,
            default.nonce,
            env,
            memory,
            tc,
        )
    };
    let mut tc_borrowed = test_fixture.tc.borrow_mut();
    let mut runtime = test_fixture.env.runtime(
        &mut tc_borrowed,
        test_fixture.addr,
        test_fixture.timestamp,
        test_fixture.nonce,
        mock_module(),
    );
    let wasm_uref = wasm_write(&mut test_fixture.memory, uref);
    let new_value = Value::Int32(2);
    let wasm_new_value = wasm_write(&mut test_fixture.memory, new_value);
    gs_write(&mut runtime, wasm_uref, wasm_new_value)
}

#[test]
fn uref_key_writeable_valid() {
    // Tests that URef key is writeable when access rights of the key allows for writing.
    test_uref_key_writeable(AccessRights::Write).expect("Writing to writeable URef should work.")
}

#[test]
fn uref_key_writeable_invalid() {
    // Tests that writing to URef which is not writeable fails.
    let result = test_uref_key_writeable(AccessRights::Read);
    assert_invalid_access(result);
}

fn test_uref_key_addable(rights: AccessRights) -> Result<(), wasmi::Trap> {
    let init_value = Value::Int32(1);
    let mut rng = rand::thread_rng();
    // URef we will be trying to read.
    let uref = random_uref_key(&mut rng, rights);
    let mut test_fixture: TestFixture = {
        // We need to put `uref`, which we will be using later, to `known_urefs` set
        // of the context's account. Otherwise we will get ForgedReference error.
        let known_urefs: HashSet<Key> = once(uref).collect();
        let empty_uref_map = urefs_map(std::iter::empty());
        let default: TestFixture = Default::default();
        let (key, account) = mock_account(default.addr);
        let env = MockEnv::new(key, empty_uref_map, known_urefs, account.clone(), 0);
        let memory = env.memory_manager();
        let mut init_tc = mock_tc(key, &account);
        init_tc.write(uref, init_value.clone());
        let tc = Rc::new(RefCell::new(init_tc));
        TestFixture::new(
            default.addr,
            default.timestamp,
            default.nonce,
            env,
            memory,
            tc,
        )
    };
    let mut tc_borrowed = test_fixture.tc.borrow_mut();
    let mut runtime = test_fixture.env.runtime(
        &mut tc_borrowed,
        test_fixture.addr,
        test_fixture.timestamp,
        test_fixture.nonce,
        mock_module(),
    );
    let wasm_uref = wasm_write(&mut test_fixture.memory, uref);
    let new_value = Value::Int32(2);
    let wasm_new_value = wasm_write(&mut test_fixture.memory, new_value);
    runtime.add(
        wasm_uref.0,
        wasm_uref.1 as u32,
        wasm_new_value.0,
        wasm_new_value.1 as u32,
    )
}
#[test]
fn uref_key_addable_valid() {
    // Tests that URef key is addable when access rights of the key allows for adding.
    test_uref_key_addable(AccessRights::Add)
        .expect("Adding to URef when it is Addable should work.")
}

#[test]
fn uref_key_addable_invalid() {
    // Tests that adding to URef which is not addable fails.
    let result = test_uref_key_addable(AccessRights::Read);
    assert_invalid_access(result);
}
