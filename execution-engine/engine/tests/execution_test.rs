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
use common::key::{AccessRights, Key};
use common::value::{self, Account, Contract, Value};
use execution_engine::execution::Runtime;
use execution_engine::runtime_context::RuntimeContext;
use execution_engine::trackingcopy::TrackingCopy;
use execution_engine::Validated;
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
use storage::global_state::inmem::*;
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

impl WasmMemoryManager {
    pub fn new(memory: MemoryRef) -> Self {
        WasmMemoryManager { memory, offset: 0 }
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

    pub fn read<T: FromBytes>(&self, offset: u32, len: usize) -> Result<T, Error> {
        let bytes = self.read_bytes(offset, len)?;
        deserialize(&bytes).map_err(Into::into)
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

// Helper method that writes `value` to `wasm_memory`
fn wasm_write<T: ToBytes>(wasm_memory: &mut WasmMemoryManager, value: T) -> (u32, usize) {
    wasm_memory.write(value)
}

fn wasm_read<T: FromBytes>(
    wasm_memory: &mut WasmMemoryManager,
    wasm_ptr: (u32, usize),
) -> Result<T, Error> {
    wasm_memory.read(wasm_ptr.0, wasm_ptr.1)
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

    fn with_known_urefs(&mut self, new_urefs: HashSet<Key>) {
        self.env.known_urefs.extend(new_urefs);
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
    let mut rng = rand::thread_rng();
    let uref = random_uref_key(&mut rng, AccessRights::READ_WRITE);
    test_fixture.with_known_urefs(once(uref).collect());
    let mut tc_borrowed = test_fixture.tc.borrow_mut();
    let mut runtime = test_fixture.env.runtime(
        &mut tc_borrowed,
        test_fixture.addr,
        test_fixture.timestamp,
        test_fixture.nonce,
        mock_module(),
    );

    // Use uref as the key to perform an action on the global state.
    // This should succeed because the uref is valid.
    runtime
        .write(uref, Value::Int32(43))
        .expect("writing using valid uref should succeed");
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
    let wasm_uref = wasm_write(
        &mut test_fixture.memory,
        random_uref_key(&mut rng, AccessRights::READ_WRITE),
    );

    let uref: Key = wasm_read(&mut test_fixture.memory, wasm_uref).unwrap();

    // Use uref as the key to perform an action on the global state.
    // This should fail because the uref was forged
    let trap = runtime.write(uref, Value::Int32(42));

    assert_error_contains(trap, "ForgedReference");
}

use execution_engine::execution::rename_export_to_call;

// Transforms Wasm module and URef map into Contract.
//
// Renames "name" function to "call" in the passed Wasm module.
// This is necessary because host runtime will do the same thing prior to saving it.
fn contract_bytes_from_wat(mut test_module: TestModule) -> Vec<u8> {
    rename_export_to_call(&mut test_module.module, test_module.func_name);
    parity_wasm::serialize(test_module.module).expect("Failed to serialize Wasm module.")
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

    let fn_bytes = contract_bytes_from_wat(wasm_module.clone());

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

        let hash = runtime
            .store_function(fn_bytes.clone(), urefs.clone())
            .expect("store_function should succeed");

        Key::Hash(hash)
    };

    // Test that Runtime stored contract under expected hash
    let transforms = test_fixture.tc.borrow().effect().1;
    let effect = transforms.get(&hash).unwrap();
    let contract = Value::Contract(Contract::new(fn_bytes, urefs));
    // Assert contract in the GlobalState is the one we wanted to store.
    assert_eq!(effect, &Transform::Write(contract));
}

fn assert_invalid_access<T>(result: Result<T, wasmi::Trap>) {
    assert_error_contains(result, "InvalidAccess")
}

fn assert_forged_reference<T>(result: Result<T, execution_engine::execution::Error>) {
    match result {
        Err(execution_engine::execution::Error::ForgedReference(_)) => assert!(true),
        _ => panic!("Error. Test should have failed with ForgedReference error but didn't."),
    }
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
    let uref = random_uref_key(&mut rng, AccessRights::READ);
    let urefs = urefs_map(vec![("ForgedURef".to_owned(), uref)]);

    let mut tc_borrowed = test_fixture.tc.borrow_mut();
    let mut runtime = test_fixture.env.runtime(
        &mut tc_borrowed,
        test_fixture.addr,
        test_fixture.timestamp,
        test_fixture.nonce,
        wasm_module.module.clone(),
    );

    let contract = contract_bytes_from_wat(wasm_module.clone());

    let store_result = runtime.store_function(contract.to_bytes().unwrap(), urefs);

    // Since we don't know the urefs we wanted to store together with the contract
    assert_forged_reference(store_result);
}

#[test]
fn store_contract_hash_legal_urefs() {
    // Tests that storing function (contract) with valid (known) uref works.
    // Test fixtures
    let mut test_fixture: TestFixture = Default::default();
    let mut rng = rand::thread_rng();
    let uref = random_uref_key(&mut rng, AccessRights::READ_WRITE);
    test_fixture.with_known_urefs(once(uref).collect());
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

        let urefs = urefs_map(vec![
            ("KnownURef".to_owned(), uref),
            ("PublicHash".to_owned(), random_contract_key(&mut rng)),
        ]);

        let fn_bytes = contract_bytes_from_wat(wasm_module.clone());

        // This is the FFI call that Wasm triggers when it stores a contract in GS.
        let hash = runtime
            .store_function(fn_bytes.clone(), urefs.clone())
            .expect("store_function should succeed");

        (
            Key::Hash(hash),
            Value::Contract(Contract::new(fn_bytes, urefs)),
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
    let contract_uref = random_uref_key(&mut rng, AccessRights::READ_WRITE);
    // URef we want to store WITH the contract so that it can use it later
    let known_uref = random_uref_key(&mut rng, AccessRights::READ_WRITE);
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

        let fn_bytes = contract_bytes_from_wat(wasm_module);
        let contract = Value::Contract(Contract::new(fn_bytes, urefs.clone()));

        // This is the FFI call that Wasm triggers when it stores a contract in GS.
        runtime
            .write(contract_uref, contract.clone())
            .expect("Write should succeed.");

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
    let forged_contract_uref = random_uref_key(&mut rng, AccessRights::READ_WRITE);
    // URef we want to store WITH the contract so that it can use it later
    let known_uref = random_uref_key(&mut rng, AccessRights::READ_WRITE);
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

    let fn_bytes = contract_bytes_from_wat(wasm_module);
    let contract = Value::Contract(Contract::new(fn_bytes, urefs.clone()));

    // This is the FFI call that Wasm triggers when it stores a contract in GS.
    let result = runtime.write(forged_contract_uref, contract);
    assert_error_contains(result, "ForgedReference");
}

#[test]
fn account_key_writeable() {
    // Tests that account key is not writeable.
    // Test fixtures
    let mut test_fixture: TestFixture = Default::default();
    let mut rng = rand::thread_rng();
    let wasm_module = create_wasm_module();

    let account_key = random_account_key(&mut rng);

    let mut tc_borrowed = test_fixture.tc.borrow_mut();
    let mut runtime = test_fixture.env.runtime(
        &mut tc_borrowed,
        test_fixture.addr,
        test_fixture.timestamp,
        test_fixture.nonce,
        wasm_module.module.clone(),
    );

    let result = runtime.write(account_key, Value::Int32(1));
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
    tc_borrowed.write(Validated(account_key), Validated(init_value));
    let mut runtime = test_fixture.env.runtime(
        &mut tc_borrowed,
        test_fixture.addr,
        test_fixture.timestamp,
        test_fixture.nonce,
        wasm_module.module.clone(),
    );

    runtime.read(account_key)
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
    // This is the key we will want to add to an account
    let additional_key = ("PublichHash#2".to_owned(), random_contract_key(&mut rng));
    let named_key = Value::NamedKey(additional_key.0.clone(), additional_key.1);
    {
        let mut tc_borrowed = test_fixture.tc.borrow_mut();
        // Write an account under current context's key
        tc_borrowed.write(
            Validated(Key::Account(test_fixture.addr)),
            Validated(Value::Account(account.clone())),
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
            .add(account_key, named_key)
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
        .get(&Validated(Key::Account(test_fixture.addr)))
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

    let some_other_account = random_account_key(&mut rng);
    // We will try to add keys to an account that is not current context.
    let mut tc_borrowed = test_fixture.tc.borrow_mut();
    // Write an account under current context's key
    tc_borrowed.write(
        Validated(some_other_account),
        Validated(Value::Account(account.clone())),
    );

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
    let result = runtime.add(some_other_account, named_key);

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

    let fn_bytes = contract_bytes_from_wat(wasm_module.clone());
    let contract = Value::Contract(Contract::new(fn_bytes, urefs.clone()));

    let mut tc_borrowed = test_fixture.tc.borrow_mut();
    let mut runtime = test_fixture.env.runtime(
        &mut tc_borrowed,
        test_fixture.addr,
        test_fixture.timestamp,
        test_fixture.nonce,
        wasm_module.module.clone(),
    );

    let result = runtime.write(contract_key, contract);
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

        tc.write(
            Validated(base_key),
            Validated(Value::Contract(init_contract.clone())),
        );

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
    let mut rng = rand::thread_rng();
    let additional_key = ("PublichHash#2".to_owned(), random_contract_key(&mut rng));
    // This is the key we will want to add to a contract
    let named_key = Value::NamedKey(additional_key.0, additional_key.1);

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
    runtime.add(add_to_key, named_key)
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
        init_tc.write(Validated(uref), Validated(init_value.clone()));
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
    runtime.read(uref)
}

#[test]
fn uref_key_readable_valid() {
    // Tests that URef key is readable when access rights of the key allows for reading.
    let init_value = Value::Int32(1);
    let test_result = test_uref_key_readable(init_value.clone(), AccessRights::READ)
        .expect("Reading from GS should work.");
    assert_eq!(test_result, init_value);
}

#[test]
fn uref_key_readable_invalid() {
    // Tests that reading URef which is not readable fails.
    let init_value = Value::Int32(1);
    let test_result = test_uref_key_readable(init_value.clone(), AccessRights::ADD);
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
        init_tc.write(Validated(uref), Validated(init_value.clone()));
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
    let new_value = Value::Int32(2);
    runtime.write(uref, new_value)
}

#[test]
fn uref_key_writeable_valid() {
    // Tests that URef key is writeable when access rights of the key allows for writing.
    test_uref_key_writeable(AccessRights::WRITE).expect("Writing to writeable URef should work.")
}

#[test]
fn uref_key_writeable_invalid() {
    // Tests that writing to URef which is not writeable fails.
    let result = test_uref_key_writeable(AccessRights::READ);
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
        init_tc.write(Validated(uref), Validated(init_value.clone()));
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
    let new_value = Value::Int32(2);
    runtime.add(uref, new_value)
}
#[test]
fn uref_key_addable_valid() {
    // Tests that URef key is addable when access rights of the key allows for adding.
    test_uref_key_addable(AccessRights::ADD)
        .expect("Adding to URef when it is Addable should work.")
}

#[test]
fn uref_key_addable_invalid() {
    // Tests that adding to URef which is not addable fails.
    let result = test_uref_key_addable(AccessRights::READ);
    assert_invalid_access(result);
}
