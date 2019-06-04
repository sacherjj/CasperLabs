use std::cell::RefCell;
use std::collections::{BTreeMap, HashMap, HashSet};
use std::fmt;
use std::iter::IntoIterator;
use std::rc::Rc;

use blake2::digest::VariableOutput;
use blake2::VarBlake2b;
use itertools::Itertools;
use parity_wasm::elements::{Error as ParityWasmError, Module};
use rand::SeedableRng;
use rand_chacha::ChaChaRng;
use wasmi::{
    Error as InterpreterError, Externals, HostError, ImportsBuilder, MemoryRef, ModuleInstance,
    ModuleRef, RuntimeArgs, RuntimeValue, Trap,
};

use common::bytesrepr::{deserialize, Error as BytesReprError, ToBytes};
use common::key::{AccessRights, Key};
use common::value::Value;
use shared::newtypes::Validated;
use shared::transform::TypeMismatch;
use storage::global_state::StateReader;

use args::Args;
use engine_state::execution_effect::ExecutionEffect;
use functions::{
    ADD_FUNC_INDEX, ADD_UREF_FUNC_INDEX, CALL_CONTRACT_FUNC_INDEX, GAS_FUNC_INDEX,
    GET_ARG_FUNC_INDEX, GET_CALL_RESULT_FUNC_INDEX, GET_FN_FUNC_INDEX, GET_READ_FUNC_INDEX,
    GET_UREF_FUNC_INDEX, HAS_UREF_FUNC_INDEX, LOAD_ARG_FUNC_INDEX, NEW_FUNC_INDEX,
    PROTOCOL_VERSION_FUNC_INDEX, READ_FUNC_INDEX, RET_FUNC_INDEX, REVERT_FUNC_INDEX, SEED_FN_INDEX,
    SER_FN_FUNC_INDEX, STORE_FN_INDEX, WRITE_FUNC_INDEX,
};
use resolvers::create_module_resolver;
use resolvers::error::ResolverError;
use resolvers::memory_resolver::MemoryResolver;
use runtime_context::RuntimeContext;
use tracking_copy::TrackingCopy;
use URefAddr;

#[derive(Debug)]
pub enum Error {
    Interpreter(InterpreterError),
    Storage(storage::error::Error),
    BytesRepr(BytesReprError),
    KeyNotFound(Key),
    TypeMismatch(TypeMismatch),
    Overflow,
    InvalidAccess {
        required: AccessRights,
    },
    ForgedReference(Key),
    ArgIndexOutOfBounds(usize),
    URefNotFound(String),
    FunctionNotFound(String),
    ParityWasm(ParityWasmError),
    GasLimit,
    Ret(Vec<Key>),
    Rng(rand::Error),
    ResolverError(ResolverError),
    /// Reverts execution with a provided status
    Revert(u32),
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
    fn from(error: !) -> Error {
        match error {}
    }
}

impl From<ResolverError> for Error {
    fn from(err: ResolverError) -> Error {
        Error::ResolverError(err)
    }
}

impl HostError for Error {}

pub struct Runtime<'a, R> {
    memory: MemoryRef,
    module: Module,
    result: Vec<u8>,
    host_buf: Vec<u8>,
    context: RuntimeContext<'a, R>,
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
    pub fn new(memory: MemoryRef, module: Module, context: RuntimeContext<'a, R>) -> Self {
        Runtime {
            memory,
            module,
            result: Vec::new(),
            host_buf: Vec::new(),
            context,
        }
    }

    /// Charge specified amount of gas
    ///
    /// Returns false if gas limit exceeded and true if not.
    /// Intuition about the return value sense is to aswer the question 'are we allowed to continue?'
    fn charge_gas(&mut self, amount: u64) -> bool {
        let prev = self.context.gas_counter();
        match prev.checked_add(amount) {
            // gas charge overflow protection
            None => false,
            Some(val) if val > self.context.gas_limit() => false,
            Some(val) => {
                self.context.set_gas_counter(val);
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

    fn string_from_mem(&self, ptr: u32, size: u32) -> Result<String, Trap> {
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
        if i < self.context.args().len() {
            self.host_buf = self.context.args()[i].clone();
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

    pub fn add_uref(
        &mut self,
        name_ptr: u32,
        name_size: u32,
        key_ptr: u32,
        key_size: u32,
    ) -> Result<(), Trap> {
        let name = self.string_from_mem(name_ptr, name_size)?;
        let key = self.key_from_mem(key_ptr, key_size)?;
        self.context.add_uref(name, key).map_err(Into::into)
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
        let (args, module, mut refs, protocol_version) = {
            match self.context.read_gs(&key)? {
                None => Err(Error::KeyNotFound(key)),
                Some(value) => {
                    if let Value::Contract(contract) = value {
                        let args: Vec<Vec<u8>> = deserialize(&args_bytes)?;
                        let module = parity_wasm::deserialize_buffer(contract.bytes())?;

                        Ok((
                            args,
                            module,
                            contract.urefs_lookup().clone(),
                            contract.protocol_version(),
                        ))
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
        let result = sub_call(
            module,
            args,
            &mut refs,
            key,
            self,
            extra_urefs,
            protocol_version,
        )?;
        self.host_buf = result;
        Ok(self.host_buf.len())
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
        let contract = common::value::contract::Contract::new(
            fn_bytes,
            urefs,
            self.context.protocol_version(),
        );
        let new_hash = self.context.store_contract(contract.into())?;
        Ok(new_hash)
    }

    /// Writes function address (`hash_bytes`) into the Wasm memory (at `dest_ptr` pointer).
    fn function_address(&mut self, hash_bytes: [u8; 32], dest_ptr: u32) -> Result<(), Trap> {
        self.memory
            .set(dest_ptr, &hash_bytes)
            .map_err(|e| Error::Interpreter(e).into())
    }

    /// Generates new unforgable reference and adds it to the context's known_uref set.
    pub fn new_uref(&mut self, key_ptr: u32, value_ptr: u32, value_size: u32) -> Result<(), Trap> {
        let value = self.value_from_mem(value_ptr, value_size)?; // read initial value from memory
        let key = self.context.new_uref(value)?;
        self.memory
            .set(key_ptr, &key.to_bytes().map_err(Error::BytesRepr)?)
            .map_err(|e| Error::Interpreter(e).into())
    }

    /// Writes `value` under `key` in GlobalState.
    pub fn write(
        &mut self,
        key_ptr: u32,
        key_size: u32,
        value_ptr: u32,
        value_size: u32,
    ) -> Result<(), Trap> {
        let key = self.key_from_mem(key_ptr, key_size)?;
        let value = self.value_from_mem(value_ptr, value_size)?;
        self.context.write_gs(key, value).map_err(Into::into)
    }

    /// Adds `value` to the cell that `key` points at.
    pub fn add(
        &mut self,
        key_ptr: u32,
        key_size: u32,
        value_ptr: u32,
        value_size: u32,
    ) -> Result<(), Trap> {
        let key = self.key_from_mem(key_ptr, key_size)?;
        let value = self.value_from_mem(value_ptr, value_size)?;
        self.context.add_gs(key, value).map_err(Into::into)
    }

    /// Reads value from the GS living under key specified by `key_ptr` and `key_size`.
    /// Wasm and host communicate through memory that Wasm module exports.
    /// If contract wants to pass data to the host, it has to tell it [the host]
    /// where this data lives in the exported memory (pass its pointer and length).
    pub fn read(&mut self, key_ptr: u32, key_size: u32) -> Result<usize, Trap> {
        let key = self.key_from_mem(key_ptr, key_size)?;
        let value = err_on_missing_key(key, self.context.read_gs(&key))?;
        let value_bytes = value.to_bytes().map_err(Error::BytesRepr)?;
        self.host_buf = value_bytes;
        Ok(self.host_buf.len())
    }

    /// Writes the seed associated with the [`RuntimeContext`] to the given destination
    /// in runtime memory.
    fn write_seed(&mut self, dest_ptr: u32) -> Result<(), Trap> {
        let seed = self.context.seed();
        self.memory
            .set(dest_ptr, &seed)
            .map_err(|e| Error::Interpreter(e).into())
    }

    /// Reverts contract execution with a status specified.
    pub fn revert(&mut self, status: u32) -> Trap {
        Error::Revert(status).into()
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
                let size = self.read(key_ptr, key_size)?;
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

            PROTOCOL_VERSION_FUNC_INDEX => Ok(Some(self.context.protocol_version().into())),

            REVERT_FUNC_INDEX => {
                // args(0) = status u32
                let status = Args::parse(args)?;

                Err(self.revert(status))
            }

            SEED_FN_INDEX => {
                let dest_ptr = Args::parse(args)?;
                self.write_seed(dest_ptr)?;
                Ok(None)
            }

            _ => panic!("unknown function index"),
        }
    }
}

fn instance_and_memory(
    parity_module: Module,
    protocol_version: u64,
) -> Result<(ModuleRef, MemoryRef), Error> {
    let module = wasmi::Module::from_parity_wasm_module(parity_module)?;
    let resolver = create_module_resolver(protocol_version)?;
    let mut imports = ImportsBuilder::new();
    imports.push_resolver("env", &resolver);
    let instance = ModuleInstance::new(&module, &imports)?.assert_no_start();

    let memory = resolver.memory_ref()?;
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
    protocol_version: u64,
) -> Result<Vec<u8>, Error>
where
    R::Error: Into<Error>,
{
    let (instance, memory) = instance_and_memory(parity_module.clone(), protocol_version)?;

    let known_urefs = vec_key_rights_to_map(refs.values().cloned().chain(extra_urefs));
    let rng = ChaChaRng::from_rng(current_runtime.context.rng().clone()).map_err(Error::Rng)?;
    let mut runtime = Runtime {
        memory,
        module: parity_module,
        result: Vec::new(),
        host_buf: Vec::new(),
        context: RuntimeContext::new(
            current_runtime.context.state(),
            refs,
            known_urefs,
            args,
            current_runtime.context.account(),
            key,
            current_runtime.context.gas_limit(),
            current_runtime.context.gas_counter(),
            current_runtime.context.fn_store_id(),
            rng,
            protocol_version,
        ),
    };

    let result = instance.invoke_export("call", &[], &mut runtime);

    match result {
        Ok(_) => Ok(runtime.result),
        Err(e) => {
            if let Some(host_error) = e.as_host_error() {
                // If the "error" was in fact a trap caused by calling `ret` then
                // this is normal operation and we should return the value captured
                // in the Runtime result field.
                let downcasted_error = host_error.downcast_ref::<Error>().unwrap();
                match downcasted_error {
                    Error::Ret(ref ret_urefs) => {
                        //insert extra urefs returned from call
                        let ret_urefs_map: HashMap<URefAddr, HashSet<AccessRights>> =
                            vec_key_rights_to_map(ret_urefs.clone());
                        current_runtime.context.add_urefs(ret_urefs_map);
                        return Ok(runtime.result);
                    }
                    Error::Revert(status) => {
                        // Propagate revert as revert, instead of passing it as
                        // InterpreterError.
                        return Err(Error::Revert(*status));
                    }
                    _ => {}
                }
            }
            Err(Error::Interpreter(e))
        }
    }
}

/// Groups vector of keys by their address and accumulates access rights per key.
pub fn vec_key_rights_to_map<I: IntoIterator<Item = Key>>(
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

pub fn create_rng(account_addr: &[u8; 32], timestamp: u64, nonce: u64) -> ChaChaRng {
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
        account_addr: [u8; 32],
        timestamp: u64,
        nonce: u64,
        gas_limit: u64,
        protocol_version: u64,
        tc: Rc<RefCell<TrackingCopy<R>>>,
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
        account_addr: [u8; 32],
        timestamp: u64,
        nonce: u64,
        gas_limit: u64,
        protocol_version: u64,
        tc: Rc<RefCell<TrackingCopy<R>>>,
    ) -> (Result<ExecutionEffect, Error>, u64)
    where
        R::Error: Into<Error>,
    {
        let acct_key = Key::Account(account_addr);
        let (instance, memory) = on_fail_charge!(
            instance_and_memory(parity_module.clone(), protocol_version),
            0
        );
        #[allow(unreachable_code)]
        let validated_key = on_fail_charge!(Validated::new(acct_key, Validated::valid), 0);
        let value = on_fail_charge! {
            match tc.borrow_mut().get(&validated_key) {
                Ok(None) => Err(Error::KeyNotFound(acct_key)),
                Err(error) => Err(error.into()),
                Ok(Some(value)) => Ok(value)
            },
            0
        };
        let account = value.as_account();
        let mut uref_lookup_local = account.urefs_lookup().clone();
        let known_urefs: HashMap<URefAddr, HashSet<AccessRights>> =
            vec_key_rights_to_map(uref_lookup_local.values().cloned());
        let rng = create_rng(&account_addr, timestamp, nonce);
        let gas_counter = 0u64;
        let fn_store_id = 0u32;
        let arguments: Vec<Vec<u8>> = if args.is_empty() {
            Vec::new()
        } else {
            // TODO: figure out how this works with the cost model
            // https://casperlabs.atlassian.net/browse/EE-239
            on_fail_charge!(deserialize(args), 0)
        };
        let context = RuntimeContext::new(
            tc,
            &mut uref_lookup_local,
            known_urefs,
            arguments,
            &account,
            acct_key,
            gas_limit,
            gas_counter,
            fn_store_id,
            rng,
            protocol_version,
        );
        let mut runtime = Runtime::new(memory, parity_module, context);
        on_fail_charge!(
            instance.invoke_export("call", &[], &mut runtime),
            runtime.context.gas_counter()
        );

        (Ok(runtime.context.effect()), runtime.context.gas_counter())
    }
}

/// Turns `key` into a `([u8; 32], AccessRights)` tuple.
/// Returns None if `key` is not `Key::URef` as it wouldn't have `AccessRights` associated with it.
/// Helper function for creating `known_urefs` associating addresses and corresponding `AccessRights`.
pub fn key_to_tuple(key: Key) -> Option<([u8; 32], AccessRights)> {
    match key {
        Key::URef(raw_addr, rights) => Some((raw_addr, rights)),
        Key::Account(_) => None,
        Key::Hash(_) => None,
        Key::Local { .. } => None,
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
