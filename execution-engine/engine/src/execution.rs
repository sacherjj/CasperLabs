extern crate blake2;

use self::blake2::digest::{Input, VariableOutput};
use self::blake2::VarBlake2b;
use common::bytesrepr::{deserialize, Error as BytesReprError, ToBytes};
use common::key::{AccessRights, Key};
use common::value::{Account, Value};
use storage::gs::{StateReader, ExecutionEffect};
use storage::transform::TypeMismatch;
use trackingcopy::{AddResult, TrackingCopy};
use wasmi::memory_units::Pages;
use wasmi::{
    Error as InterpreterError, Externals, FuncInstance, FuncRef, HostError, ImportsBuilder,
    MemoryDescriptor, MemoryInstance, MemoryRef, ModuleImportResolver, ModuleInstance, ModuleRef,
    RuntimeArgs, RuntimeValue, Signature, Trap, ValueType,
};

use argsparser::Args;
use parity_wasm::elements::{Error as ParityWasmError, Module};
use rand::{RngCore, SeedableRng};
use rand_chacha::ChaChaRng;
use std::cell::RefCell;
use std::collections::{BTreeMap, HashMap};
use std::fmt;

#[derive(Debug)]
pub enum Error {
    Interpreter(InterpreterError),
    Storage(storage::error::Error),
    BytesRepr(BytesReprError),
    KeyNotFound(Key),
    TypeMismatch(TypeMismatch),
    Overflow,
    InvalidAccess { required: AccessRights },
    ForgedReference(Key),
    NoImportedMemory,
    ArgIndexOutOfBounds(usize),
    URefNotFound(String),
    FunctionNotFound(String),
    ParityWasm(ParityWasmError),
    GasLimit,
    Ret(Vec<Key>),
    Rng(rand::Error),
    Unreachable,
}

impl fmt::Display for Error {
    fn fmt(&self, f: &mut fmt::Formatter) -> fmt::Result {
        write!(f, "{:?}", self)
    }
}

impl From<ParityWasmError> for Error {
    fn from(e: ParityWasmError) -> Self {
        Error::ParityWasm(e)
    }
}

impl From<InterpreterError> for Error {
    fn from(e: InterpreterError) -> Self {
        Error::Interpreter(e)
    }
}

impl From<storage::error::Error> for Error {
    fn from(e: storage::error::Error) -> Self {
        Error::Storage(e)
    }
}

impl From<BytesReprError> for Error {
    fn from(e: BytesReprError) -> Self {
        Error::BytesRepr(e)
    }
}

impl From<!> for Error {
    fn from(_err: !) -> Error {
        Error::Unreachable
    }
}

impl HostError for Error {}

type URefAddr = [u8; 32];

/// Holds information specific to the deployed contract.
pub struct RuntimeContext<'a> {
    // Enables look up of specific uref based on human-readable name
    uref_lookup: &'a mut BTreeMap<String, Key>,
    // Used to check uref is known before use (prevents forging urefs)
    known_urefs: HashMap<URefAddr, AccessRights>,
    account: &'a Account,
    // Key pointing to the entity we are currently running
    //(could point at an account or contract in the global state)
    base_key: Key,
    gas_limit: u64,
}

impl<'a> RuntimeContext<'a> {
    pub fn new(
        uref_lookup: &'a mut BTreeMap<String, Key>,
        account: &'a Account,
        base_key: Key,
        gas_limit: u64,
    ) -> Self {
        RuntimeContext {
            uref_lookup,
            known_urefs: HashMap::new(),
            account,
            base_key,
            gas_limit,
        }
    }

    pub fn insert_named_uref(&mut self, name: String, key: Key) {
        self.insert_uref(key);
        self.uref_lookup.insert(name, key);
    }

    pub fn insert_uref(&mut self, key: Key) {
        if let Key::URef(raw_addr, rights) = key {
            self.known_urefs.insert(raw_addr, rights);
        }
    }

    /// Validates whether keys used in the `value` are not forged.
    fn validate_keys(&self, value: Value) -> Result<Value, Error> {
        match value {
            non_key @ Value::Int32(_)
            | non_key @ Value::UInt128(_)
            | non_key @ Value::UInt256(_)
            | non_key @ Value::UInt512(_)
            | non_key @ Value::ByteArray(_)
            | non_key @ Value::ListInt32(_)
            | non_key @ Value::String(_)
            | non_key @ Value::ListString(_) => Ok(non_key),
            Value::NamedKey(name, key) => {
                self.validate_key(&key).map(|_| Value::NamedKey(name, key))
            }
            Value::Account(account) => {
                // This should never happen as accounts can't be created by contracts.
                // I am putting this here for the sake of completness.
                account
                    .urefs_lookup()
                    .values()
                    .try_for_each(|key| self.validate_key(key))
                    .map(|_| Value::Account(account))
            }
            Value::Contract(contract) => contract
                .urefs_lookup()
                .values()
                .try_for_each(|key| self.validate_key(key))
                .map(|_| Value::Contract(contract)),
        }
    }

    /// Validates whether key is not forged (whether it can be found in the `known_urefs`)
    /// and whether the version of a key that contract wants to use, has access rights
    /// that are less powerful than access rights' of the key in the `known_urefs`.
    fn validate_key(&self, key: &Key) -> Result<(), Error> {
        match key {
            Key::URef(raw_addr, new_rights) => {
                self.known_urefs
                    .get(raw_addr) // Check if we `key` is known
                    .filter(|known_rights| *known_rights >= new_rights) // are we allowed to use it this way?
                    .map(|_| ()) // at this point we know it's valid to use `key`
                    .ok_or_else(|| Error::ForgedReference(*key)) // otherwise `key` is forged
            }
            _ => Ok(()),
        }
    }

    pub fn deserialize_key(&self, bytes: &[u8]) -> Result<Key, Error> {
        let key: Key = deserialize(bytes)?;
        self.validate_key(&key).map(|_| key)
    }

    pub fn deserialize_keys(&self, bytes: &[u8]) -> Result<Vec<Key>, Error> {
        let keys: Vec<Key> = deserialize(bytes)?;
        keys.iter().try_for_each(|k| self.validate_key(k))?;
        Ok(keys)
    }
}

pub struct Runtime<'a, R: StateReader<Key, Value>> {
    args: Vec<Vec<u8>>,
    memory: MemoryRef,
    state: &'a mut TrackingCopy<R>,
    module: Module,
    result: Vec<u8>,
    host_buf: Vec<u8>,
    fn_store_id: u32,
    gas_counter: u64,
    context: RuntimeContext<'a>,
    rng: ChaChaRng,
}

/// Rename function called `name` in the `module` to `call`.
/// wasmi's entrypoint for a contracts is a function called `call`,
/// so we have to rename function before storing it in the GlobalState.
pub fn rename_export_to_call(module: &mut Module, name: String) {
    let main_export = module
        .export_section_mut()
        .unwrap()
        .entries_mut()
        .iter_mut()
        .find(|e| e.field() == name)
        .unwrap()
        .field_mut();
    main_export.clear();
    main_export.push_str("call");
}

impl<'a, R: StateReader<Key, Value>> Runtime<'a, R>
where
    R::Error: Into<Error>,
{
    #[allow(clippy::too_many_arguments)]
    pub fn new(
        args: Vec<Vec<u8>>,
        memory: MemoryRef,
        state: &'a mut TrackingCopy<R>,
        module: Module,
        account_addr: [u8; 20],
        nonce: u64,
        timestamp: u64,
        context: RuntimeContext<'a>,
    ) -> Self {
        let rng = create_rng(&account_addr, timestamp, nonce);
        Runtime {
            args,
            memory,
            state,
            module,
            result: Vec::new(),
            host_buf: Vec::new(),
            fn_store_id: 0,
            gas_counter: 0,
            context,
            rng,
        }
    }

    /// Charge specified amount of gas
    ///
    /// Returns false if gas limit exceeded and true if not.
    /// Intuition about the return value sense is to aswer the question 'are we allowed to continue?'
    fn charge_gas(&mut self, amount: u64) -> bool {
        let prev = self.gas_counter;
        match prev.checked_add(amount) {
            // gas charge overflow protection
            None => false,
            Some(val) if val > self.context.gas_limit => false,
            Some(val) => {
                self.gas_counter = val;
                true
            }
        }
    }

    fn gas(&mut self, amount: u64) -> Result<(), Trap> {
        if self.charge_gas(amount) {
            Ok(())
        } else {
            Err(Error::GasLimit.into())
        }
    }

    fn effect(&self) -> ExecutionEffect {
        self.state.effect()
    }

    fn key_from_mem(&mut self, key_ptr: u32, key_size: u32) -> Result<Key, Error> {
        let bytes = self.memory.get(key_ptr, key_size as usize)?;
        self.context.deserialize_key(&bytes)
    }

    fn value_from_mem(&mut self, value_ptr: u32, value_size: u32) -> Result<Value, Error> {
        let bytes = self.memory.get(value_ptr, value_size as usize)?;
        deserialize(&bytes)
            .map_err(Into::into)
            .and_then(|v| self.context.validate_keys(v))
    }

    fn string_from_mem(&mut self, ptr: u32, size: u32) -> Result<String, Trap> {
        let bytes = self
            .memory
            .get(ptr, size as usize)
            .map_err(Error::Interpreter)?;
        deserialize(&bytes).map_err(|e| Error::BytesRepr(e).into())
    }

    fn get_function_by_name(&mut self, name_ptr: u32, name_size: u32) -> Result<Vec<u8>, Trap> {
        let name = self.string_from_mem(name_ptr, name_size)?;

        let has_name: bool = self
            .module
            .export_section()
            .and_then(|es| es.entries().iter().find(|e| e.field() == name))
            .is_some();

        if has_name {
            let mut module = self.module.clone();
            // We only want the function exported under `name` to be callable;
            //`optimize` removes all code that is not reachable from the exports
            // listed in the second argument.
            pwasm_utils::optimize(&mut module, vec![&name]).unwrap();
            rename_export_to_call(&mut module, name);

            parity_wasm::serialize(module).map_err(|e| Error::ParityWasm(e).into())
        } else {
            Err(Error::FunctionNotFound(name).into())
        }
    }

    fn kv_from_mem(
        &mut self,
        key_ptr: u32,
        key_size: u32,
        value_ptr: u32,
        value_size: u32,
    ) -> Result<(Key, Value), Error> {
        let key = self.key_from_mem(key_ptr, key_size)?;
        let value = self.value_from_mem(value_ptr, value_size)?;
        Ok((key, value))
    }

    /// Load the i-th argument invoked as part of a `sub_call` into
    /// the runtime buffer so that a subsequent `get_arg` can return it
    /// to the caller.
    pub fn load_arg(&mut self, i: usize) -> Result<usize, Trap> {
        if i < self.args.len() {
            self.host_buf = self.args[i].clone();
            Ok(self.host_buf.len())
        } else {
            Err(Error::ArgIndexOutOfBounds(i).into())
        }
    }

    /// Load the uref known by the given name into the Wasm memory
    pub fn get_uref(&mut self, name_ptr: u32, name_size: u32, dest_ptr: u32) -> Result<(), Trap> {
        let name = self.string_from_mem(name_ptr, name_size)?;
        let uref = self
            .context
            .uref_lookup
            .get(&name)
            .ok_or_else(|| Error::URefNotFound(name))?;
        let uref_bytes = uref.to_bytes().map_err(Error::BytesRepr)?;

        self.memory
            .set(dest_ptr, &uref_bytes)
            .map_err(|e| Error::Interpreter(e).into())
    }

    pub fn has_uref(&mut self, name_ptr: u32, name_size: u32) -> Result<i32, Trap> {
        let name = self.string_from_mem(name_ptr, name_size)?;
        if self.context.uref_lookup.contains_key(&name) {
            Ok(0)
        } else {
            Ok(1)
        }
    }

    pub fn add_uref(
        &mut self,
        name_ptr: u32,
        name_size: u32,
        key_ptr: u32,
        key_size: u32,
    ) -> Result<(), Trap> {
        let name = self.string_from_mem(name_ptr, name_size)?;
        let key = self.key_from_mem(key_ptr, key_size)?;
        self.context.insert_named_uref(name.clone(), key);
        let base_key = self.context.base_key;
        self.add_transforms(base_key, Value::NamedKey(name, key))
    }

    pub fn set_mem_from_buf(&mut self, dest_ptr: u32) -> Result<(), Trap> {
        self.memory
            .set(dest_ptr, &self.host_buf)
            .map_err(|e| Error::Interpreter(e).into())
    }

    /// Return a some bytes from the memory and terminate the current `sub_call`.
    /// Note that the return type is `Trap`, indicating that this function will
    /// always kill the current Wasm instance.
    pub fn ret(
        &mut self,
        value_ptr: u32,
        value_size: usize,
        extra_urefs_ptr: u32,
        extra_urefs_size: usize,
    ) -> Trap {
        let mem_get = self
            .memory
            .get(value_ptr, value_size)
            .map_err(Error::Interpreter)
            .and_then(|x| {
                let urefs_bytes = self.memory.get(extra_urefs_ptr, extra_urefs_size)?;
                let urefs = self.context.deserialize_keys(&urefs_bytes)?;
                Ok((x, urefs))
            });
        match mem_get {
            Ok((buf, urefs)) => {
                // Set the result field in the runtime and return
                // the proper element of the `Error` enum indicating
                // that the reason for exiting the module was a call to ret.
                self.result = buf;
                Error::Ret(urefs).into()
            }
            Err(e) => e.into(),
        }
    }

    pub fn call_contract(
        &mut self,
        key_ptr: u32,
        key_size: usize,
        args_ptr: u32,
        args_size: usize,
        extra_urefs_ptr: u32,
        extra_urefs_size: usize,
    ) -> Result<usize, Error> {
        let key_bytes = self.memory.get(key_ptr, key_size)?;
        let args_bytes = self.memory.get(args_ptr, args_size)?;
        let urefs_bytes = self.memory.get(extra_urefs_ptr, extra_urefs_size)?;

        let key = self.context.deserialize_key(&key_bytes)?;
        if self.is_readable(&key) {
            let (args, module, mut refs) = {
                match self.state.read(key).map_err(Into::into)? {
                    None => Err(Error::KeyNotFound(key)),
                    Some(value) => {
                        if let Value::Contract(contract) = value {
                            let args: Vec<Vec<u8>> = deserialize(&args_bytes)?;
                            let module = parity_wasm::deserialize_buffer(contract.bytes())?;

                            Ok((args, module, contract.urefs_lookup().clone()))
                        } else {
                            Err(Error::FunctionNotFound(format!(
                                "Value at {:?} is not a contract",
                                key
                            )))
                        }
                    }
                }
            }?;

            let extra_urefs = self.context.deserialize_keys(&urefs_bytes)?;
            let result = sub_call(module, args, &mut refs, key, self, extra_urefs)?;
            self.host_buf = result;
            Ok(self.host_buf.len())
        } else {
            Err(Error::InvalidAccess {
                required: AccessRights::Read,
            })
        }
    }

    pub fn serialize_function(&mut self, name_ptr: u32, name_size: u32) -> Result<usize, Trap> {
        let fn_bytes = self.get_function_by_name(name_ptr, name_size)?;
        self.host_buf = fn_bytes;
        Ok(self.host_buf.len())
    }

    /// Tries to store a function, located in the Wasm memory, into the GlobalState
    /// and writes back a function's hash at `hash_ptr` in the Wasm memory.
    ///
    /// `name_ptr` and `name_size` tell the host where to look for a function's name.
    /// Once it knows the name it can search for this exported function in the Wasm module.
    /// Note that functions that contract wants to store have to be marked with `export` keyword.
    /// `urefs_ptr` and `urefs_size` describe when the additional unforgable references can be found.
    pub fn store_function(
        &mut self,
        name_ptr: u32,
        name_size: u32,
        urefs_ptr: u32,
        urefs_size: u32,
        hash_ptr: u32,
    ) -> Result<(), Trap> {
        let fn_bytes = self.get_function_by_name(name_ptr, name_size)?;
        let uref_bytes = self
            .memory
            .get(urefs_ptr, urefs_size as usize)
            .map_err(Error::Interpreter)?;
        let urefs: BTreeMap<String, Key> = deserialize(&uref_bytes).map_err(Error::BytesRepr)?;
        urefs
            .iter()
            .try_for_each(|(_, v)| self.context.validate_key(&v))?;
        let contract = common::value::Contract::new(fn_bytes, urefs);
        let new_hash = self.new_function_address()?;
        self.state
            .write(Key::Hash(new_hash), Value::Contract(contract));
        self.function_address(new_hash, hash_ptr)
    }

    /// Generates new function address.
    /// Function address is deterministic. It is a hash of public key, nonce and `fn_store_id`,
    /// which is a counter that is being incremented after every function generation.
    /// If function address was based only on account's public key and deploy's nonce,
    /// then all function addresses generated within one deploy would have been the same.
    fn new_function_address(&mut self) -> Result<[u8; 32], Error> {
        let mut pre_hash_bytes = Vec::with_capacity(44); //32 byte pk + 8 byte nonce + 4 byte ID
        pre_hash_bytes.extend_from_slice(self.context.account.pub_key());
        pre_hash_bytes.append(&mut self.context.account.nonce().to_bytes()?);
        pre_hash_bytes.append(&mut self.fn_store_id.to_bytes()?);

        self.fn_store_id += 1;

        let mut hasher = VarBlake2b::new(32).unwrap();
        hasher.input(&pre_hash_bytes);
        let mut hash_bytes = [0; 32];
        hasher.variable_result(|hash| hash_bytes.clone_from_slice(hash));
        Ok(hash_bytes)
    }

    /// Writes function address (`hash_bytes`) into the Wasm memory (at `dest_ptr` pointer).
    fn function_address(&mut self, hash_bytes: [u8; 32], dest_ptr: u32) -> Result<(), Trap> {
        self.memory
            .set(dest_ptr, &hash_bytes)
            .map_err(|e| Error::Interpreter(e).into())
    }

    /// Writes value under a key (specified by their pointer and length properties from the Wasm memory).
    pub fn write(
        &mut self,
        key_ptr: u32,
        key_size: u32,
        value_ptr: u32,
        value_size: u32,
    ) -> Result<(), Trap> {
        self.kv_from_mem(key_ptr, key_size, value_ptr, value_size)
            .and_then(|(key, value)| {
                if self.is_writeable(&key) {
                    self.state.write(key, value);
                    Ok(())
                } else {
                    Err(Error::InvalidAccess {
                        required: AccessRights::Write,
                    })
                }
            })
            .map_err(Into::into)
    }

    pub fn add(
        &mut self,
        key_ptr: u32,
        key_size: u32,
        value_ptr: u32,
        value_size: u32,
    ) -> Result<(), Trap> {
        let (key, value) = self.kv_from_mem(key_ptr, key_size, value_ptr, value_size)?;
        self.add_transforms(key, value)
    }

    // Tests whether reading from the `key` is valid.
    // For Accounts it's valid to read when the operation is done on the current context's key.
    // For Contracts it's always valid.
    // For URefs it's valid if the access rights of the URef allow for reading.
    fn is_readable(&self, key: &Key) -> bool {
        match key {
            Key::Account(_) => &self.context.base_key == key,
            Key::Hash(_) => true,
            Key::URef(_, rights) => rights.is_readable(),
        }
    }

    /// Tests whether addition to `key` is valid.
    /// Addition to account key is valid iff it is being made from the context of the account.
    /// Addition to contract key is valid iff it is being made from the context of the contract.
    /// Additions to unforgeable key is valid as long as key itself is addable
    fn is_addable(&self, key: &Key) -> bool {
        match key {
            Key::Account(_) | Key::Hash(_) => &self.context.base_key == key,
            Key::URef(_, rights) => rights.is_addable(),
        }
    }

    // Test whether writing to `kay` is valid.
    // For Accounts and Hashes it's always invalid.
    // For URefs it depends on the access rights that uref has.
    fn is_writeable(&self, key: &Key) -> bool {
        match key {
            Key::Account(_) | Key::Hash(_) => false,
            Key::URef(_, rights) => rights.is_writeable(),
        }
    }

    /// Reads value living under a key (found at `key_ptr` and `key_size` in Wasm memory).
    /// Fails if `key` is not "readable", i.e. its access rights are weaker than `AccessRights::Read`.
    fn value_from_key(&mut self, key_ptr: u32, key_size: u32) -> Result<Value, Trap> {
        let key = self.key_from_mem(key_ptr, key_size)?;
        if self.is_readable(&key) {
            err_on_missing_key(key, self.state.read(key)).map_err(Into::into)
        } else {
            Err(Error::InvalidAccess {
                required: AccessRights::Read,
            }
            .into())
        }
    }

    /// Adds `value` to the `key`. The premise for being able to `add` value is that
    /// the type of it [value] can be added (is a Monoid). If the values can't be added,
    /// either because they're not a Monoid or if the value stored under `key` has different type,
    /// then `TypeMismatch` errors is returned. Addition can also fail when `key` is not "addable".
    fn add_transforms(&mut self, key: Key, value: Value) -> Result<(), Trap> {
        if self.is_addable(&key) {
            match self.state.add(key, value) {
                Err(storage_error) => Err(storage_error.into().into()),
                Ok(AddResult::Success) => Ok(()),
                Ok(AddResult::KeyNotFound(key)) => Err(Error::KeyNotFound(key).into()),
                Ok(AddResult::TypeMismatch(type_mismatch)) => {
                    Err(Error::TypeMismatch(type_mismatch).into())
                }
                Ok(AddResult::Overflow) => Err(Error::Overflow.into()),
            }
        } else {
            Err(Error::InvalidAccess {
                required: AccessRights::Add,
            }
            .into())
        }
    }

    /// Reads value from the GS living under key specified by `key_ptr` and `key_size`.
    /// Wasm and host communicate through memory that Wasm module exports.
    /// If contract wants to pass data to the host, it has to tell it [the host]
    /// where this data lives in the exported memory (pass its pointer and length).
    pub fn read_value(&mut self, key_ptr: u32, key_size: u32) -> Result<usize, Trap> {
        let value_bytes = {
            let value = self.value_from_key(key_ptr, key_size)?;
            value.to_bytes().map_err(Error::BytesRepr)?
        };
        self.host_buf = value_bytes;
        Ok(self.host_buf.len())
    }

    /// Generates new unforgable reference and adds it to the context's known_uref set.
    pub fn new_uref(&mut self, key_ptr: u32, value_ptr: u32, value_size: u32) -> Result<(), Trap> {
        let value = self.value_from_mem(value_ptr, value_size)?; // read initial value from memory
        let mut key = [0u8; 32];
        self.rng.fill_bytes(&mut key);
        let key = Key::URef(key, AccessRights::ReadWrite);
        self.state.write(key, value); // write initial value to state
        self.context.insert_uref(key);
        self.memory
            .set(key_ptr, &key.to_bytes().map_err(Error::BytesRepr)?)
            .map_err(|e| Error::Interpreter(e).into())
    }
}

// Helper function for turning result of lookup into domain values.
fn err_on_missing_key<A, E>(key: Key, r: Result<Option<A>, E>) -> Result<A, Error>
where
    E: Into<Error>,
{
    match r {
        Ok(None) => Err(Error::KeyNotFound(key)),
        Err(error) => Err(error.into()),
        Ok(Some(v)) => Ok(v),
    }
}

fn as_usize(u: u32) -> usize {
    u as usize
}

const WRITE_FUNC_INDEX: usize = 0;
const READ_FUNC_INDEX: usize = 1;
const ADD_FUNC_INDEX: usize = 2;
const NEW_FUNC_INDEX: usize = 3;
const GET_READ_FUNC_INDEX: usize = 4;
const SER_FN_FUNC_INDEX: usize = 5;
const GET_FN_FUNC_INDEX: usize = 6;
const LOAD_ARG_FUNC_INDEX: usize = 7;
const GET_ARG_FUNC_INDEX: usize = 8;
const RET_FUNC_INDEX: usize = 9;
const GET_CALL_RESULT_FUNC_INDEX: usize = 10;
const CALL_CONTRACT_FUNC_INDEX: usize = 11;
const GET_UREF_FUNC_INDEX: usize = 12;
const GAS_FUNC_INDEX: usize = 13;
const HAS_UREF_FUNC_INDEX: usize = 14;
const ADD_UREF_FUNC_INDEX: usize = 15;
const STORE_FN_INDEX: usize = 16;

impl<'a, R: StateReader<Key, Value>> Externals for Runtime<'a, R>
where
    R::Error: Into<Error>,
{
    fn invoke_index(
        &mut self,
        index: usize,
        args: RuntimeArgs,
    ) -> Result<Option<RuntimeValue>, Trap> {
        match index {
            READ_FUNC_INDEX => {
                // args(0) = pointer to key in Wasm memory
                // args(1) = size of key in Wasm memory
                let (key_ptr, key_size) = Args::parse(args)?;
                let size = self.read_value(key_ptr, key_size)?;
                Ok(Some(RuntimeValue::I32(size as i32)))
            }

            SER_FN_FUNC_INDEX => {
                // args(0) = pointer to name in Wasm memory
                // args(1) = size of name in Wasm memory
                let (name_ptr, name_size) = Args::parse(args)?;
                let size = self.serialize_function(name_ptr, name_size)?;
                Ok(Some(RuntimeValue::I32(size as i32)))
            }

            WRITE_FUNC_INDEX => {
                // args(0) = pointer to key in Wasm memory
                // args(1) = size of key
                // args(2) = pointer to value
                // args(3) = size of value
                let (key_ptr, key_size, value_ptr, value_size) = Args::parse(args)?;
                self.write(key_ptr, key_size, value_ptr, value_size)?;
                Ok(None)
            }

            ADD_FUNC_INDEX => {
                // args(0) = pointer to key in Wasm memory
                // args(1) = size of key
                // args(2) = pointer to value
                // args(3) = size of value
                let (key_ptr, key_size, value_ptr, value_size) = Args::parse(args)?;
                self.add(key_ptr, key_size, value_ptr, value_size)?;
                Ok(None)
            }

            NEW_FUNC_INDEX => {
                // args(0) = pointer to key destination in Wasm memory
                // args(1) = pointer to initial value
                // args(2) = size of initial value
                let (key_ptr, value_ptr, value_size) = Args::parse(args)?;
                self.new_uref(key_ptr, value_ptr, value_size)?;
                Ok(None)
            }

            GET_READ_FUNC_INDEX => {
                // args(0) = pointer to destination in Wasm memory
                let dest_ptr = Args::parse(args)?;
                self.set_mem_from_buf(dest_ptr)?;
                Ok(None)
            }

            GET_FN_FUNC_INDEX => {
                // args(0) = pointer to destination in Wasm memory
                let dest_ptr = Args::parse(args)?;
                self.set_mem_from_buf(dest_ptr)?;
                Ok(None)
            }

            LOAD_ARG_FUNC_INDEX => {
                // args(0) = index of host runtime arg to load
                let i = Args::parse(args)?;
                let size = self.load_arg(i)?;
                Ok(Some(RuntimeValue::I32(size as i32)))
            }

            GET_ARG_FUNC_INDEX => {
                // args(0) = pointer to destination in Wasm memory
                let dest_ptr = Args::parse(args)?;
                self.set_mem_from_buf(dest_ptr)?;
                Ok(None)
            }

            RET_FUNC_INDEX => {
                // args(0) = pointer to value
                // args(1) = size of value
                // args(2) = pointer to extra returned urefs
                // args(3) = size of extra urefs
                let (value_ptr, value_size, extra_urefs_ptr, extra_urefs_size) = Args::parse(args)?;

                Err(self.ret(
                    value_ptr,
                    as_usize(value_size),
                    extra_urefs_ptr,
                    as_usize(extra_urefs_size),
                ))
            }

            CALL_CONTRACT_FUNC_INDEX => {
                // args(0) = pointer to key where contract is at in global state
                // args(1) = size of key
                // args(2) = pointer to function arguments in Wasm memory
                // args(3) = size of arguments
                // args(4) = pointer to extra supplied urefs
                // args(5) = size of extra urefs
                let (key_ptr, key_size, args_ptr, args_size, extra_urefs_ptr, extra_urefs_size) =
                    Args::parse(args)?;

                let size = self.call_contract(
                    key_ptr,
                    as_usize(key_size),
                    args_ptr,
                    as_usize(args_size),
                    extra_urefs_ptr,
                    as_usize(extra_urefs_size),
                )?;
                Ok(Some(RuntimeValue::I32(size as i32)))
            }

            GET_CALL_RESULT_FUNC_INDEX => {
                // args(0) = pointer to destination in Wasm memory
                let dest_ptr = Args::parse(args)?;
                self.set_mem_from_buf(dest_ptr)?;
                Ok(None)
            }

            GET_UREF_FUNC_INDEX => {
                // args(0) = pointer to uref name in Wasm memory
                // args(1) = size of uref name
                // args(2) = pointer to destination in Wasm memory
                let (name_ptr, name_size, dest_ptr) = Args::parse(args)?;
                self.get_uref(name_ptr, name_size, dest_ptr)?;
                Ok(None)
            }

            HAS_UREF_FUNC_INDEX => {
                // args(0) = pointer to uref name in Wasm memory
                // args(1) = size of uref name
                let (name_ptr, name_size) = Args::parse(args)?;
                let result = self.has_uref(name_ptr, name_size)?;
                Ok(Some(RuntimeValue::I32(result)))
            }

            ADD_UREF_FUNC_INDEX => {
                // args(0) = pointer to uref name in Wasm memory
                // args(1) = size of uref name
                // args(2) = pointer to destination in Wasm memory
                let (name_ptr, name_size, key_ptr, key_size) = Args::parse(args)?;
                self.add_uref(name_ptr, name_size, key_ptr, key_size)?;
                Ok(None)
            }

            GAS_FUNC_INDEX => {
                let gas: u32 = Args::parse(args)?;
                self.gas(u64::from(gas))?;
                Ok(None)
            }

            STORE_FN_INDEX => {
                // args(0) = pointer to function name in Wasm memory
                // args(1) = size of the name
                // args(2) = pointer to additional unforgable names
                //           to be saved with the function body
                // args(3) = size of the additional unforgable names
                // args(4) = pointer to a Wasm memory where we will save
                //           hash of the new function
                let (name_ptr, name_size, urefs_ptr, urefs_size, hash_ptr) = Args::parse(args)?;
                self.store_function(name_ptr, name_size, urefs_ptr, urefs_size, hash_ptr)?;
                Ok(None)
            }

            _ => panic!("unknown function index"),
        }
    }
}

pub struct RuntimeModuleImportResolver {
    memory: RefCell<Option<MemoryRef>>,
    max_memory: u32,
}

impl Default for RuntimeModuleImportResolver {
    fn default() -> Self {
        RuntimeModuleImportResolver {
            memory: RefCell::new(None),
            max_memory: 256,
        }
    }
}

impl RuntimeModuleImportResolver {
    pub fn new() -> RuntimeModuleImportResolver {
        Default::default()
    }

    pub fn mem_ref(&self) -> Result<MemoryRef, Error> {
        let maybe_mem: &Option<MemoryRef> = &self.memory.borrow();
        match maybe_mem {
            Some(mem) => Ok(mem.clone()),
            None => Err(Error::NoImportedMemory),
        }
    }
}

impl ModuleImportResolver for RuntimeModuleImportResolver {
    fn resolve_func(
        &self,
        field_name: &str,
        _signature: &Signature,
    ) -> Result<FuncRef, InterpreterError> {
        let func_ref = match field_name {
            "read_value" => FuncInstance::alloc_host(
                Signature::new(&[ValueType::I32; 2][..], Some(ValueType::I32)),
                READ_FUNC_INDEX,
            ),
            "serialize_function" => FuncInstance::alloc_host(
                Signature::new(&[ValueType::I32; 2][..], Some(ValueType::I32)),
                SER_FN_FUNC_INDEX,
            ),
            "write" => FuncInstance::alloc_host(
                Signature::new(&[ValueType::I32; 4][..], None),
                WRITE_FUNC_INDEX,
            ),
            "get_read" => FuncInstance::alloc_host(
                Signature::new(&[ValueType::I32; 1][..], None),
                GET_READ_FUNC_INDEX,
            ),
            "get_function" => FuncInstance::alloc_host(
                Signature::new(&[ValueType::I32; 1][..], None),
                GET_FN_FUNC_INDEX,
            ),
            "add" => FuncInstance::alloc_host(
                Signature::new(&[ValueType::I32; 4][..], None),
                ADD_FUNC_INDEX,
            ),
            "new_uref" => FuncInstance::alloc_host(
                Signature::new(&[ValueType::I32; 3][..], None),
                NEW_FUNC_INDEX,
            ),
            "load_arg" => FuncInstance::alloc_host(
                Signature::new(&[ValueType::I32; 1][..], Some(ValueType::I32)),
                LOAD_ARG_FUNC_INDEX,
            ),
            "get_arg" => FuncInstance::alloc_host(
                Signature::new(&[ValueType::I32; 1][..], None),
                GET_ARG_FUNC_INDEX,
            ),
            "ret" => FuncInstance::alloc_host(
                Signature::new(&[ValueType::I32; 4][..], None),
                RET_FUNC_INDEX,
            ),
            "call_contract" => FuncInstance::alloc_host(
                Signature::new(&[ValueType::I32; 6][..], Some(ValueType::I32)),
                CALL_CONTRACT_FUNC_INDEX,
            ),
            "get_call_result" => FuncInstance::alloc_host(
                Signature::new(&[ValueType::I32; 1][..], None),
                GET_CALL_RESULT_FUNC_INDEX,
            ),
            "get_uref" => FuncInstance::alloc_host(
                Signature::new(&[ValueType::I32; 3][..], None),
                GET_UREF_FUNC_INDEX,
            ),
            "has_uref_name" => FuncInstance::alloc_host(
                Signature::new(&[ValueType::I32; 2][..], Some(ValueType::I32)),
                HAS_UREF_FUNC_INDEX,
            ),
            "add_uref" => FuncInstance::alloc_host(
                Signature::new(&[ValueType::I32; 4][..], None),
                ADD_UREF_FUNC_INDEX,
            ),
            "gas" => FuncInstance::alloc_host(
                Signature::new(&[ValueType::I32; 1][..], None),
                GAS_FUNC_INDEX,
            ),
            "store_function" => FuncInstance::alloc_host(
                Signature::new(&[ValueType::I32; 5][..], None),
                STORE_FN_INDEX,
            ),
            _ => {
                return Err(InterpreterError::Function(format!(
                    "host module doesn't export function with name {}",
                    field_name
                )));
            }
        };
        Ok(func_ref)
    }

    fn resolve_memory(
        &self,
        field_name: &str,
        descriptor: &MemoryDescriptor,
    ) -> Result<MemoryRef, InterpreterError> {
        if field_name == "memory" {
            let effective_max = descriptor.maximum().unwrap_or(self.max_memory + 1);
            if descriptor.initial() > self.max_memory || effective_max > self.max_memory {
                Err(InterpreterError::Instantiation(
                    "Module requested too much memory".to_owned(),
                ))
            } else {
                // Note: each "page" is 64 KiB
                let mem = MemoryInstance::alloc(
                    Pages(descriptor.initial() as usize),
                    descriptor.maximum().map(|x| Pages(x as usize)),
                )?;
                *self.memory.borrow_mut() = Some(mem.clone());
                Ok(mem)
            }
        } else {
            Err(InterpreterError::Instantiation(
                "Memory imported under unknown name".to_owned(),
            ))
        }
    }
}

fn instance_and_memory(parity_module: Module) -> Result<(ModuleRef, MemoryRef), Error> {
    let module = wasmi::Module::from_parity_wasm_module(parity_module)?;
    let resolver = RuntimeModuleImportResolver::new();
    let mut imports = ImportsBuilder::new();
    imports.push_resolver("env", &resolver);
    let instance = ModuleInstance::new(&module, &imports)?.assert_no_start();

    let memory = resolver.mem_ref()?;
    Ok((instance, memory))
}

fn sub_call<R: StateReader<Key, Value>>(
    parity_module: Module,
    args: Vec<Vec<u8>>,
    refs: &mut BTreeMap<String, Key>,
    key: Key,
    current_runtime: &mut Runtime<R>,
    // Unforgable references passed across the call boundary from caller to callee
    //(necessary if the contract takes a uref argument).
    extra_urefs: Vec<Key>,
) -> Result<Vec<u8>, Error>
where
    R::Error: Into<Error>,
{
    let (instance, memory) = instance_and_memory(parity_module.clone())?;
    let known_urefs = refs
        .values()
        .cloned()
        .chain(extra_urefs)
        .map(key_to_tuple)
        .flatten()
        .collect();
    let rng = ChaChaRng::from_rng(&mut current_runtime.rng).map_err(Error::Rng)?;
    let mut runtime = Runtime {
        args,
        memory,
        state: current_runtime.state,
        module: parity_module,
        result: Vec::new(),
        host_buf: Vec::new(),
        fn_store_id: 0,
        gas_counter: current_runtime.gas_counter,
        context: RuntimeContext {
            uref_lookup: refs,
            known_urefs,
            account: current_runtime.context.account,
            base_key: key,
            gas_limit: current_runtime.context.gas_limit,
        },
        rng,
    };

    let result = instance.invoke_export("call", &[], &mut runtime);

    match result {
        Ok(_) => Ok(runtime.result),
        Err(e) => {
            if let Some(host_error) = e.as_host_error() {
                // If the "error" was in fact a trap caused by calling `ret` then
                // this is normal operation and we should return the value captured
                // in the Runtime result field.
                if let Error::Ret(ret_urefs) = host_error.downcast_ref::<Error>().unwrap() {
                    //insert extra urefs returned from call
                    let ret_urefs_map: HashMap<URefAddr, AccessRights> = ret_urefs
                        .iter()
                        .map(|e| key_to_tuple(*e))
                        .flatten()
                        .collect();
                    current_runtime.context.known_urefs.extend(ret_urefs_map);
                    return Ok(runtime.result);
                }
            }
            Err(Error::Interpreter(e))
        }
    }
}

fn create_rng(account_addr: &[u8; 20], timestamp: u64, nonce: u64) -> ChaChaRng {
    let mut seed: [u8; 32] = [0u8; 32];
    let mut data: Vec<u8> = Vec::new();
    let hasher = VarBlake2b::new(32).unwrap();
    data.extend(account_addr);
    data.extend_from_slice(&timestamp.to_le_bytes());
    data.extend_from_slice(&nonce.to_le_bytes());
    hasher.variable_result(|hash| seed.clone_from_slice(hash));
    ChaChaRng::from_seed(seed)
}

#[macro_export]
macro_rules! on_fail_charge {
    ($fn:expr, $cost:expr) => {
        match $fn {
            Ok(res) => res,
            Err(er) => {
                let lambda = || $cost;
                return (Err(er.into()), lambda());
            }
        }
    };
}

pub trait Executor<A> {
    #[allow(clippy::too_many_arguments)]
    fn exec<R: StateReader<Key, Value>>(
        &self,
        parity_module: A,
        args: &[u8],
        account_addr: [u8; 20],
        timestamp: u64,
        nonce: u64,
        gas_limit: u64,
        tc: &mut TrackingCopy<R>,
    ) -> (Result<ExecutionEffect, Error>, u64)
    where
        R::Error: Into<Error>;
}

pub struct WasmiExecutor;

impl Executor<Module> for WasmiExecutor {
    fn exec<R: StateReader<Key, Value>>(
        &self,
        parity_module: Module,
        args: &[u8],
        account_addr: [u8; 20],
        timestamp: u64,
        nonce: u64,
        gas_limit: u64,
        tc: &mut TrackingCopy<R>,
    ) -> (Result<ExecutionEffect, Error>, u64)
    where
        R::Error: Into<Error>,
    {
        let (instance, memory) = on_fail_charge!(instance_and_memory(parity_module.clone()), 0);
        let acct_key = Key::Account(account_addr);
        let value = on_fail_charge! {
        match tc.get(&acct_key) {
            Ok(None) => Err(Error::KeyNotFound(acct_key)),
            Err(error) => Err(error.into()),
            Ok(Some(value)) => Ok(value)
        }, 0 };
        let account = value.as_account();
        let mut uref_lookup_local = account.urefs_lookup().clone();
        let known_urefs: HashMap<URefAddr, AccessRights> = uref_lookup_local
            .values()
            .cloned()
            .map(key_to_tuple)
            .flatten()
            .collect();
        let context = RuntimeContext {
            uref_lookup: &mut uref_lookup_local,
            known_urefs,
            account: &account,
            base_key: acct_key,
            gas_limit,
        };
        let arguments: Vec<Vec<u8>> = if args.is_empty() {
            Vec::new()
        } else {
            // TODO: figure out how this works with the cost model
            // https://casperlabs.atlassian.net/browse/EE-239
            on_fail_charge!(deserialize(args), 0)
        };
        let mut runtime = Runtime::new(
            arguments,
            memory,
            tc,
            parity_module,
            account_addr,
            nonce,
            timestamp,
            context,
        );
        let _ = on_fail_charge!(
            instance.invoke_export("call", &[], &mut runtime),
            runtime.gas_counter
        );

        (Ok(runtime.effect()), runtime.gas_counter)
    }
}

/// Turns `key` into a `([u8; 32], AccessRights)` tuple.
/// Returns None if `key` is not `Key::URef` as we it wouldn't have
/// `AccessRights` associated to it.
/// This is helper function for creating `known_urefs` map which
/// holds addresses and corresponding `AccessRights`.
pub fn key_to_tuple(key: Key) -> Option<([u8; 32], AccessRights)> {
    match key {
        Key::URef(raw_addr, rights) => Some((raw_addr, rights)),
        Key::Account(_) => None,
        Key::Hash(_) => None,
    }
}

#[cfg(test)]
mod tests {
    // Need intermediate method b/c when on_fail_charge macro is inlined
    // for the error case it will call return which would exit the test.
    fn indirect_fn(r: Result<u32, String>, f: u32) -> (Result<u32, String>, u32) {
        let res = on_fail_charge!(r, f);
        (Ok(res), 1111) // 1111 for easy discrimination
    }

    #[test]
    fn on_fail_charge_ok() {
        let counter = 0;
        let ok: Result<u32, String> = Ok(10);
        let res: (Result<u32, String>, u32) = indirect_fn(ok, counter);
        assert!(res.0.is_ok());
        assert_eq!(res.0.ok().unwrap(), 10);
        assert_eq!(res.1, 1111);
        assert_eq!(counter, 0); // test that lambda was not executed for the Ok-case
    }

    #[test]
    fn on_fail_charge_laziness() {
        // Need this indirection b/c otherwise compiler complains
        // about borrowing counter.counter after it was moved in the `fail` call.
        struct Counter {
            pub counter: u32,
        };
        impl Counter {
            fn fail(&mut self) -> Result<u32, String> {
                self.counter += 10;
                Err("Err".to_owned())
            }
        }
        let mut counter = Counter { counter: 1 };
        let res: (Result<u32, String>, u32) = indirect_fn(counter.fail(), counter.counter);
        assert!(res.0.is_err());
        assert_eq!(res.1, 11); // test that counter value was fetched lazily
    }
}
