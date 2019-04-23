extern crate blake2;

use self::blake2::digest::{Input, VariableOutput};
use self::blake2::VarBlake2b;
use common::bytesrepr::{deserialize, Error as BytesReprError, ToBytes};
use common::key::{AccessRights, Key};
use common::value::Value;
use storage::global_state::{ExecutionEffect, StateReader};
use storage::transform::TypeMismatch;
use trackingcopy::{AddResult, TrackingCopy};
use wasmi::memory_units::Pages;
use wasmi::{
    Error as InterpreterError, Externals, FuncInstance, FuncRef, HostError, ImportsBuilder,
    MemoryDescriptor, MemoryInstance, MemoryRef, ModuleImportResolver, ModuleInstance, ModuleRef,
    RuntimeArgs, RuntimeValue, Signature, Trap, ValueType,
};

use argsparser::Args;
use itertools::Itertools;
use parity_wasm::elements::{Error as ParityWasmError, Module};
use rand::{RngCore, SeedableRng};
use rand_chacha::ChaChaRng;
use std::cell::RefCell;
use std::collections::{BTreeMap, HashMap, HashSet};
use std::fmt;
use std::iter::IntoIterator;

use super::runtime_context::RuntimeContext;
use super::URefAddr;
use super::Validated;

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
            Some(val) if val > self.context.gas_limit() => false,
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

    fn bytes_from_mem(&self, ptr: u32, size: usize) -> Result<Vec<u8>, Error> {
        self.memory.get(ptr, size).map_err(Into::into)
    }

    /// Reads key (defined as `key_ptr` and `key_size` tuple) from Wasm memory.
    fn key_from_mem(&mut self, key_ptr: u32, key_size: u32) -> Result<Key, Error> {
        let bytes = self.bytes_from_mem(key_ptr, key_size as usize)?;
        deserialize(&bytes).map_err(Into::into)
    }

    /// Reads value (defined as `value_ptr` and `value_size` tuple) from Wasm memory.
    fn value_from_mem(&mut self, value_ptr: u32, value_size: u32) -> Result<Value, Error> {
        let bytes = self.bytes_from_mem(value_ptr, value_size as usize)?;
        deserialize(&bytes).map_err(Into::into)
    }

    fn string_from_mem(&mut self, ptr: u32, size: u32) -> Result<String, Trap> {
        let bytes = self.bytes_from_mem(ptr, size as usize)?;
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
            .get_uref(&name)
            .ok_or_else(|| Error::URefNotFound(name))?;
        let uref_bytes = uref.to_bytes().map_err(Error::BytesRepr)?;

        self.memory
            .set(dest_ptr, &uref_bytes)
            .map_err(|e| Error::Interpreter(e).into())
    }

    pub fn has_uref(&mut self, name_ptr: u32, name_size: u32) -> Result<i32, Trap> {
        let name = self.string_from_mem(name_ptr, name_size)?;
        if self.context.contains_uref(&name) {
            Ok(0)
        } else {
            Ok(1)
        }
    }

    /// Adds `key` to the map of named keys of current context.
    pub fn add_uref(&mut self, name: String, key: Key) -> Result<(), Trap> {
        self.context.validate_key(&key)?;
        self.context.insert_named_uref(name.clone(), key);
        let base_key = self.context.base_key();
        if self.is_addable(&base_key) {
            self.add_transforms(Validated(base_key), Validated(Value::NamedKey(name, key)))
        } else {
            Err(Error::InvalidAccess {
                required: AccessRights::ADD,
            }
            .into())
        }
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
                let urefs_bytes = self.bytes_from_mem(extra_urefs_ptr, extra_urefs_size)?;
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

    /// Calls contract living under a `key`, with supplied `args` and extra `urefs`.
    pub fn call_contract(
        &mut self,
        key: Key,
        args_bytes: Vec<u8>,
        urefs_bytes: Vec<u8>,
    ) -> Result<usize, Error> {
        self.context.validate_key(&key)?;
        if self.is_readable(&key) {
            let (args, module, mut refs) = {
                match self.state.read(&Validated(key)).map_err(Into::into)? {
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
                required: AccessRights::READ,
            })
        }
    }

    pub fn serialize_function(&mut self, name_ptr: u32, name_size: u32) -> Result<usize, Trap> {
        let fn_bytes = self.get_function_by_name(name_ptr, name_size)?;
        self.host_buf = fn_bytes;
        Ok(self.host_buf.len())
    }

    /// Tries to store a function, represented as bytes from the Wasm memory, into the GlobalState
    /// and writes back a function's hash at `hash_ptr` in the Wasm memory.
    pub fn store_function(
        &mut self,
        fn_bytes: Vec<u8>,
        urefs: BTreeMap<String, Key>,
    ) -> Result<[u8; 32], Error> {
        urefs
            .iter()
            .try_for_each(|(_, v)| self.context.validate_key(&v))?;
        let contract = Value::Contract(common::value::contract::Contract::new(fn_bytes, urefs));
        let new_hash = self.new_function_address()?;
        self.state
            .write(Validated(Key::Hash(new_hash)), Validated(contract));
        Ok(new_hash)
    }

    /// Generates new function address.
    /// Function address is deterministic. It is a hash of public key, nonce and `fn_store_id`,
    /// which is a counter that is being incremented after every function generation.
    /// If function address was based only on account's public key and deploy's nonce,
    /// then all function addresses generated within one deploy would have been the same.
    fn new_function_address(&mut self) -> Result<[u8; 32], Error> {
        let mut pre_hash_bytes = Vec::with_capacity(44); //32 byte pk + 8 byte nonce + 4 byte ID
        pre_hash_bytes.extend_from_slice(self.context.account().pub_key());
        pre_hash_bytes.append(&mut self.context.account().nonce().to_bytes()?);
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

    /// Writes `value` under `key` in GlobalState.
    pub fn write(&mut self, key: Key, value: Value) -> Result<(), Trap> {
        self.context.validate_key(&key)?;
        self.context.validate_keys(&value)?;
        if self.is_writeable(&key) {
            self.state.write(Validated(key), Validated(value));
            Ok(())
        } else {
            Err(Error::InvalidAccess {
                required: AccessRights::WRITE,
            }
            .into())
        }
    }

    /// Adds `value` to the cell that `key` points at.
    pub fn add(&mut self, key: Key, value: Value) -> Result<(), Trap> {
        self.context.validate_key(&key)?;
        self.context.validate_keys(&value)?;
        if self.is_addable(&key) {
            self.add_transforms(Validated(key), Validated(value))
        } else {
            Err(Error::InvalidAccess {
                required: AccessRights::ADD,
            }
            .into())
        }
    }

    // Tests whether reading from the `key` is valid.
    fn is_readable(&self, key: &Key) -> bool {
        match key {
            Key::Account(_) => &self.context.base_key() == key,
            Key::Hash(_) => true,
            Key::URef(_, rights) => rights.is_readable(),
        }
    }

    /// Tests whether addition to `key` is valid.
    fn is_addable(&self, key: &Key) -> bool {
        match key {
            Key::Account(_) | Key::Hash(_) => &self.context.base_key() == key,
            Key::URef(_, rights) => rights.is_addable(),
        }
    }

    // Test whether writing to `kay` is valid.
    fn is_writeable(&self, key: &Key) -> bool {
        match key {
            Key::Account(_) | Key::Hash(_) => false,
            Key::URef(_, rights) => rights.is_writeable(),
        }
    }

    /// Reads value living under a key (found at `key_ptr` and `key_size` in Wasm memory).
    /// Fails if `key` is not "readable", i.e. its access rights are weaker than `AccessRights::Read`.
    pub fn read(&mut self, key: Key) -> Result<Value, Trap> {
        self.context.validate_key(&key)?;
        if self.is_readable(&key) {
            err_on_missing_key(key, self.state.read(&Validated(key))).map_err(Into::into)
        } else {
            Err(Error::InvalidAccess {
                required: AccessRights::READ,
            }
            .into())
        }
    }

    /// Adds `value` to the `key`. The premise for being able to `add` value is that
    /// the type of it [value] can be added (is a Monoid). If the values can't be added,
    /// either because they're not a Monoid or if the value stored under `key` has different type,
    /// then `TypeMismatch` errors is returned.
    fn add_transforms(&mut self, key: Validated<Key>, value: Validated<Value>) -> Result<(), Trap> {
        match self.state.add(key, value) {
            Err(storage_error) => Err(storage_error.into().into()),
            Ok(AddResult::Success) => Ok(()),
            Ok(AddResult::KeyNotFound(key)) => Err(Error::KeyNotFound(key).into()),
            Ok(AddResult::TypeMismatch(type_mismatch)) => {
                Err(Error::TypeMismatch(type_mismatch).into())
            }
            Ok(AddResult::Overflow) => Err(Error::Overflow.into()),
        }
    }

    /// Reads value from the GS living under key specified by `key_ptr` and `key_size`.
    /// Wasm and host communicate through memory that Wasm module exports.
    /// If contract wants to pass data to the host, it has to tell it [the host]
    /// where this data lives in the exported memory (pass its pointer and length).
    pub fn read_value(&mut self, key_ptr: u32, key_size: u32) -> Result<usize, Trap> {
        let key = self.key_from_mem(key_ptr, key_size)?;
        let value = self.read(key)?;
        let value_bytes = value.to_bytes().map_err(Error::BytesRepr)?;
        self.host_buf = value_bytes;
        Ok(self.host_buf.len())
    }

    pub fn new_uref(&mut self, value: Value) -> Result<Vec<u8>, Error> {
        let mut key = [0u8; 32];
        self.rng.fill_bytes(&mut key);
        let key = Key::URef(key, AccessRights::READ_ADD_WRITE);
        self.context.validate_keys(&value)?;
        self.state.write(Validated(key), Validated(value));
        self.context.insert_uref(key);
        key.to_bytes().map_err(Error::BytesRepr)
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
                let key = self.key_from_mem(key_ptr, key_size)?;
                let value = self.value_from_mem(value_ptr, value_size)?;
                self.write(key, value)?;
                Ok(None)
            }

            ADD_FUNC_INDEX => {
                // args(0) = pointer to key in Wasm memory
                // args(1) = size of key
                // args(2) = pointer to value
                // args(3) = size of value
                let (key_ptr, key_size, value_ptr, value_size) = Args::parse(args)?;
                let key = self.key_from_mem(key_ptr, key_size)?;
                let value = self.value_from_mem(value_ptr, value_size)?;
                self.add(key, value)?;
                Ok(None)
            }

            NEW_FUNC_INDEX => {
                // args(0) = pointer to key destination in Wasm memory
                // args(1) = pointer to initial value
                // args(2) = size of initial value
                let (key_ptr, value_ptr, value_size) = Args::parse(args)?;
                let value = self.value_from_mem(value_ptr, value_size)?;
                let key = self.new_uref(value)?;
                self.memory.set(key_ptr, &key).map_err(Error::Interpreter)?;
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

                // We have to explicitly tell rustc what type we expect as it cannot infer it otherwise.
                let _args_size_u32: u32 = args_size;
                let _extra_urefs_size_u32: u32 = extra_urefs_size;

                let key_contract: Key = self.key_from_mem(key_ptr, key_size)?;
                let args_bytes: Vec<u8> = self.bytes_from_mem(args_ptr, args_size as usize)?;
                let urefs_bytes =
                    self.bytes_from_mem(extra_urefs_ptr, extra_urefs_size as usize)?;

                let size = self.call_contract(key_contract, args_bytes, urefs_bytes)?;
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
                let name = self.string_from_mem(name_ptr, name_size)?;
                let key = self.key_from_mem(key_ptr, key_size)?;
                self.add_uref(name, key)?;
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
                let _uref_type: u32 = urefs_size;
                let fn_bytes = self.get_function_by_name(name_ptr, name_size)?;
                let uref_bytes = self
                    .memory
                    .get(urefs_ptr, urefs_size as usize)
                    .map_err(Error::Interpreter)?;
                let urefs = deserialize(&uref_bytes).map_err(Error::BytesRepr)?;
                let contract_hash = self.store_function(fn_bytes, urefs)?;
                self.function_address(contract_hash, hash_ptr)?;
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
    let known_urefs = vec_key_rights_to_map(refs.values().cloned().chain(extra_urefs));
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
        context: RuntimeContext::new(
            refs,
            known_urefs,
            current_runtime.context.account(),
            key,
            current_runtime.context.gas_limit(),
        ),
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
                    let ret_urefs_map: HashMap<URefAddr, HashSet<AccessRights>> =
                        vec_key_rights_to_map(ret_urefs.clone());
                    current_runtime.context.add_urefs(ret_urefs_map);
                    return Ok(runtime.result);
                }
            }
            Err(Error::Interpreter(e))
        }
    }
}

/// Groups vector of keys by their address and accumulates access rights per key.
fn vec_key_rights_to_map<I: IntoIterator<Item = Key>>(
    input: I,
) -> HashMap<URefAddr, HashSet<AccessRights>> {
    input
        .into_iter()
        .map(key_to_tuple)
        .flatten()
        .group_by(|(key, _)| *key)
        .into_iter()
        .map(|(key, group)| {
            (
                key,
                group.map(|(_, x)| x).collect::<HashSet<AccessRights>>(),
            )
        })
        .collect()
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
                let mut lambda = || $cost;
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
        match tc.get(&Validated(acct_key)) {
            Ok(None) => Err(Error::KeyNotFound(acct_key)),
            Err(error) => Err(error.into()),
            Ok(Some(value)) => Ok(value)
        }, 0 };
        let account = value.as_account();
        let mut uref_lookup_local = account.urefs_lookup().clone();
        let known_urefs: HashMap<URefAddr, HashSet<AccessRights>> =
            vec_key_rights_to_map(uref_lookup_local.values().cloned());
        let context = RuntimeContext::new(
            &mut uref_lookup_local,
            known_urefs,
            &account,
            acct_key,
            gas_limit,
        );
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
mod on_fail_charge_macro_tests {
    struct Counter {
        pub counter: u32,
    }

    impl Counter {
        fn count(&mut self, count: u32) -> u32 {
            self.counter += count;
            count
        }
    }

    fn on_fail_charge_test_helper(
        counter: &mut Counter,
        inc_value: u32,
        input: Result<u32, String>,
        fallback_value: u32,
    ) -> (Result<u32, String>, u32) {
        let res: u32 = on_fail_charge!(input, counter.count(inc_value));
        (Ok(res), fallback_value)
    }

    #[test]
    fn on_fail_charge_ok_test() {
        let mut cntr = Counter { counter: 0 };
        let fallback_value = 9999;
        let inc_value = 10;
        let ok_value = Ok(13);
        let res: (Result<u32, String>, u32) =
            on_fail_charge_test_helper(&mut cntr, inc_value, ok_value.clone(), fallback_value);
        assert_eq!(res.0, ok_value);
        assert_eq!(res.1, fallback_value);
        assert_eq!(cntr.counter, 0); // test that lambda was NOT executed for the Ok-case
    }

    #[test]
    fn on_fail_charge_err_laziness_test() {
        let mut cntr = Counter { counter: 1 };
        let fallback_value = 9999;
        let inc_value = 10;
        let expected_value = cntr.counter + inc_value;
        let err = Err("BOOM".to_owned());
        let res: (Result<u32, String>, u32) =
            on_fail_charge_test_helper(&mut cntr, inc_value, err.clone(), fallback_value);
        assert_eq!(res.0, err);
        assert_eq!(res.1, inc_value);
        assert_eq!(cntr.counter, expected_value) // test that lambda executed
    }
}

#[cfg(test)]
mod runtime_tests {
    use crate::execution::{rename_export_to_call, Error as ExecutionError, Runtime};
    use crate::runtime_context::RuntimeContext;
    use crate::trackingcopy::TrackingCopy;
    use crate::Validated;
    use common::bytesrepr::ToBytes;
    use common::key::{AccessRights, Key};
    use common::value::{self, Account, Contract, Value};
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

    struct TestFixture {
        addr: [u8; 20],
        timestamp: u64,
        nonce: u64,
        env: MockEnv,
        tc: Rc<RefCell<TrackingCopy<InMemGS<Key, Value>>>>,
    }

    impl TestFixture {
        fn new(
            addr: [u8; 20],
            timestamp: u64,
            nonce: u64,
            env: MockEnv,
            tc: Rc<RefCell<TrackingCopy<InMemGS<Key, Value>>>>,
        ) -> TestFixture {
            TestFixture {
                addr,
                timestamp,
                nonce,
                env,
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
            TestFixture::new(addr, timestamp, nonce, env, tc)
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

        let uref: Key = random_uref_key(&mut rng, AccessRights::READ_WRITE);

        // Use uref as the key to perform an action on the global state.
        // This should fail because the uref was forged
        let trap = runtime.write(uref, Value::Int32(42));

        assert_error_contains(trap, "ForgedReference");
    }

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

    #[allow(clippy::assertions_on_constants)]
    fn assert_forged_reference<T>(result: Result<T, ExecutionError>) {
        match result {
            Err(ExecutionError::ForgedReference(_)) => assert!(true),
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
            TestFixture::new(addr, timestamp, nonce, env, tc)
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
            TestFixture::new(addr, timestamp, nonce, env, tc)
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
    fn contract_key_readable() {
        // Tests that contracts are readable. This test checks that it is possible to execute
        // `call_contract` function which checks whether the key is readable.
        // Test fixtures
        let mut test_fixture: TestFixture = Default::default();
        let mut rng = rand::thread_rng();
        let wasm_module = create_wasm_module();

        let contract_key = random_contract_key(&mut rng);
        let empty_vec: Vec<u8> = Vec::new();
        let empty_urefs: Vec<Key> = Vec::new();

        let mut tc_borrowed = test_fixture.tc.borrow_mut();
        let mut runtime = test_fixture.env.runtime(
            &mut tc_borrowed,
            test_fixture.addr,
            test_fixture.timestamp,
            test_fixture.nonce,
            wasm_module.module.clone(),
        );

        let result = runtime.call_contract(
            contract_key,
            empty_vec.to_bytes().unwrap(),
            empty_urefs.to_bytes().unwrap(),
        );

        // call_contract call in the execution.rs (Runtime) first checks whether key is readable
        // and then fetches value from the memory. In this case it will pass "is_readable" check
        // but will not return results from the GlobalState. This is not perfect test but setting it
        // up in a way where we actually call another contract if very cumbersome.
        match result {
            Err(ExecutionError::KeyNotFound(key)) => {
                assert_eq!(key, contract_key)
            }
            Err(error) => panic!("Test failed with unexpected error {:?}", error),
            Ok(_) => panic!("Test should have failed but didn't"),
        }
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

            TestFixture::new(addr, timestamp, nonce, env, Rc::new(RefCell::new(tc)))
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
    fn test_uref_key_readable(
        init_value: Value,
        rights: AccessRights,
    ) -> Result<Value, wasmi::Trap> {
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
            let mut init_tc = mock_tc(key, &account);
            // We're putting some data under uref so that we can read it later.
            init_tc.write(Validated(uref), Validated(init_value.clone()));
            let tc = Rc::new(RefCell::new(init_tc));
            TestFixture::new(default.addr, default.timestamp, default.nonce, env, tc)
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
            let mut init_tc = mock_tc(key, &account);
            init_tc.write(Validated(uref), Validated(init_value.clone()));
            let tc = Rc::new(RefCell::new(init_tc));
            TestFixture::new(default.addr, default.timestamp, default.nonce, env, tc)
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
        test_uref_key_writeable(AccessRights::WRITE)
            .expect("Writing to writeable URef should work.")
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
            let mut init_tc = mock_tc(key, &account);
            init_tc.write(Validated(uref), Validated(init_value.clone()));
            let tc = Rc::new(RefCell::new(init_tc));
            TestFixture::new(default.addr, default.timestamp, default.nonce, env, tc)
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

}
