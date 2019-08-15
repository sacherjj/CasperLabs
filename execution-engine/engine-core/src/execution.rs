use std::cell::RefCell;
use std::collections::{BTreeMap, BTreeSet, HashMap, HashSet};
use std::convert::TryFrom;
use std::fmt;
use std::iter::IntoIterator;
use std::rc::Rc;

use blake2::digest::{Input, VariableOutput};
use blake2::VarBlake2b;
use itertools::Itertools;
use parity_wasm::elements::{Error as ParityWasmError, Module};
use rand::SeedableRng;
use rand_chacha::ChaChaRng;
use wasmi::{
    Error as InterpreterError, Externals, HostError, ImportsBuilder, MemoryRef, ModuleInstance,
    ModuleRef, RuntimeArgs, RuntimeValue, Trap, TrapKind,
};

use args::Args;
use contract_ffi::bytesrepr::{deserialize, Error as BytesReprError, ToBytes, U32_SIZE};
use contract_ffi::contract_api::argsparser::ArgsParser;
use contract_ffi::contract_api::{PurseTransferResult, TransferResult};
use contract_ffi::key::Key;
use contract_ffi::system_contracts::{self, mint};
use contract_ffi::uref::{AccessRights, URef};
use contract_ffi::value::account::{
    ActionType, AddKeyFailure, BlockTime, PublicKey, PurseId, RemoveKeyFailure,
    SetThresholdFailure, UpdateKeyFailure, Weight, PUBLIC_KEY_SIZE,
};
use contract_ffi::value::{Account, Value, U512};
use engine_shared::newtypes::CorrelationId;
use engine_shared::transform::TypeMismatch;
use engine_state::execution_result::ExecutionResult;
use engine_storage::global_state::StateReader;
use execution::Error::{KeyNotFound, URefNotFound};
use function_index::FunctionIndex;
use resolvers::create_module_resolver;
use resolvers::error::ResolverError;
use resolvers::memory_resolver::MemoryResolver;
use runtime_context::RuntimeContext;
use tracking_copy::TrackingCopy;
use URefAddr;

pub const MINT_NAME: &str = "mint";
pub const POS_NAME: &str = "pos";

#[derive(Debug)]
pub enum Error {
    Interpreter(InterpreterError),
    Storage(engine_storage::error::Error),
    BytesRepr(BytesReprError),
    KeyNotFound(Key),
    AccountNotFound(Key),
    TypeMismatch(TypeMismatch),
    InvalidAccess {
        required: AccessRights,
    },
    ForgedReference(URef),
    ArgIndexOutOfBounds(usize),
    URefNotFound(String),
    FunctionNotFound(String),
    ParityWasm(ParityWasmError),
    GasLimit,
    Ret(Vec<URef>),
    Rng(rand::Error),
    ResolverError(ResolverError),
    InvalidNonce {
        deploy_nonce: u64,
        expected_nonce: u64,
    },
    /// Reverts execution with a provided status
    Revert(u32),
    AddKeyFailure(AddKeyFailure),
    RemoveKeyFailure(RemoveKeyFailure),
    UpdateKeyFailure(UpdateKeyFailure),
    SetThresholdFailure(SetThresholdFailure),
    SystemContractError(system_contracts::error::Error),
    DeploymentAuthorizationFailure,
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

impl From<engine_storage::error::Error> for Error {
    fn from(e: engine_storage::error::Error) -> Self {
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

impl From<AddKeyFailure> for Error {
    fn from(err: AddKeyFailure) -> Error {
        Error::AddKeyFailure(err)
    }
}

impl From<RemoveKeyFailure> for Error {
    fn from(err: RemoveKeyFailure) -> Error {
        Error::RemoveKeyFailure(err)
    }
}

impl From<UpdateKeyFailure> for Error {
    fn from(err: UpdateKeyFailure) -> Error {
        Error::UpdateKeyFailure(err)
    }
}

impl From<SetThresholdFailure> for Error {
    fn from(err: SetThresholdFailure) -> Error {
        Error::SetThresholdFailure(err)
    }
}

impl From<system_contracts::error::Error> for Error {
    fn from(error: system_contracts::error::Error) -> Error {
        Error::SystemContractError(error)
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

    pub fn value_is_valid(&mut self, value_ptr: u32, value_size: u32) -> Result<bool, Trap> {
        let value = self.value_from_mem(value_ptr, value_size)?;

        Ok(self.context.validate_keys(&value).is_ok())
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
    pub fn get_uref(&mut self, name_ptr: u32, name_size: u32) -> Result<usize, Trap> {
        let name = self.string_from_mem(name_ptr, name_size)?;
        // Take an optional uref, and pass its serialized value as is.
        // This makes it easy to deserialize optional value on the other
        // side without failing the execution when the value does not exist.
        let uref = self.context.get_uref(&name).cloned();
        let uref_bytes = uref.to_bytes().map_err(Error::BytesRepr)?;

        self.host_buf = uref_bytes;
        Ok(self.host_buf.len())
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

    /// Writes current [self.host_buf] into [dest_ptr] location in Wasm memory
    /// for the contract to read.
    pub fn list_known_urefs(&mut self, dest_ptr: u32) -> Result<(), Trap> {
        self.memory
            .set(dest_ptr, &self.host_buf)
            .map_err(|e| Error::Interpreter(e).into())
    }

    fn remove_uref(&mut self, name_ptr: u32, name_size: u32) -> Result<(), Trap> {
        let name = self.string_from_mem(name_ptr, name_size)?;
        self.context.remove_uref(&name)?;
        Ok(())
    }

    /// Writes caller (deploy) account public key to [dest_ptr] in the Wasm memory.
    fn get_caller(&mut self, dest_ptr: u32) -> Result<(), Trap> {
        let key = self.context.get_caller();
        let bytes = key.to_bytes().map_err(Error::BytesRepr)?;
        self.memory
            .set(dest_ptr, &bytes)
            .map_err(|e| Error::Interpreter(e).into())
    }

    /// Writes current blocktime to [dest_ptr] in Wasm memory.
    fn get_blocktime(&self, dest_ptr: u32) -> Result<(), Trap> {
        let blocktime = self
            .context
            .get_blocktime()
            .to_bytes()
            .map_err(Error::BytesRepr)?;
        self.memory
            .set(dest_ptr, &blocktime)
            .map_err(|e| Error::Interpreter(e).into())
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
                let urefs = self.context.deserialize_urefs(&urefs_bytes)?;
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

    fn serialize_known_urefs(&mut self) -> Result<usize, Trap> {
        let bytes: Vec<u8> = self
            .context
            .list_known_urefs()
            .to_bytes()
            .map_err(Error::BytesRepr)?;
        let length = bytes.len();
        self.host_buf = bytes;
        Ok(length)
    }

    /// Tries to store a function, represented as bytes from the Wasm memory, into the GlobalState
    /// and writes back a function's hash at `hash_ptr` in the Wasm memory.
    pub fn store_function(
        &mut self,
        fn_bytes: Vec<u8>,
        urefs: BTreeMap<String, Key>,
    ) -> Result<[u8; 32], Error> {
        let contract = contract_ffi::value::contract::Contract::new(
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

    /// Writes `value` under a key derived from `key` in the "local cluster" of GlobalState
    pub fn write_local(
        &mut self,
        key_ptr: u32,
        key_size: u32,
        value_ptr: u32,
        value_size: u32,
    ) -> Result<(), Trap> {
        let key_bytes = self.bytes_from_mem(key_ptr, key_size as usize)?;
        let value = self.value_from_mem(value_ptr, value_size)?;
        self.context.write_ls(&key_bytes, value).map_err(Into::into)
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
        let value: Option<Value> = self.context.read_gs(&key)?;
        let value_bytes = value.to_bytes().map_err(Error::BytesRepr)?;
        self.host_buf = value_bytes;
        Ok(self.host_buf.len())
    }

    /// Similar to `read`, this function is for reading from the "local cluster" of global state
    pub fn read_local(&mut self, key_ptr: u32, key_size: u32) -> Result<usize, Trap> {
        let key_bytes = self.bytes_from_mem(key_ptr, key_size as usize)?;
        let value: Option<Value> = self.context.read_ls(&key_bytes)?;
        let value_bytes = value.to_bytes().map_err(Error::BytesRepr)?;
        self.host_buf = value_bytes;
        Ok(self.host_buf.len())
    }

    /// Reverts contract execution with a status specified.
    pub fn revert(&mut self, status: u32) -> Trap {
        Error::Revert(status).into()
    }

    pub fn take_context(self) -> RuntimeContext<'a, R> {
        self.context
    }

    fn add_associated_key(&mut self, public_key_ptr: u32, weight_value: u8) -> Result<i32, Trap> {
        let public_key = {
            // Public key as serialized bytes
            let source_serialized =
                self.bytes_from_mem(public_key_ptr, PUBLIC_KEY_SIZE + U32_SIZE)?;
            // Public key deserialized
            let source: PublicKey = deserialize(&source_serialized).map_err(Error::BytesRepr)?;
            source
        };
        let weight = Weight::new(weight_value);

        match self.context.add_associated_key(public_key, weight) {
            Ok(_) => Ok(0),
            // This relies on the fact that `AddKeyFailure` is represented as
            // i32 and first variant start with number `1`, so all other variants
            // are greater than the first one, so it's safe to assume `0` is success,
            // and any error is greater than 0.
            Err(Error::AddKeyFailure(e)) => Ok(e as i32),
            // Any other variant just pass as `Trap`
            Err(e) => Err(e.into()),
        }
    }

    fn remove_associated_key(&mut self, public_key_ptr: u32) -> Result<i32, Trap> {
        let public_key = {
            // Public key as serialized bytes
            let source_serialized =
                self.bytes_from_mem(public_key_ptr, PUBLIC_KEY_SIZE + U32_SIZE)?;
            // Public key deserialized
            let source: PublicKey = deserialize(&source_serialized).map_err(Error::BytesRepr)?;
            source
        };
        match self.context.remove_associated_key(public_key) {
            Ok(_) => Ok(0),
            Err(Error::RemoveKeyFailure(e)) => Ok(e as i32),
            Err(e) => Err(e.into()),
        }
    }

    fn update_associated_key(
        &mut self,
        public_key_ptr: u32,
        weight_value: u8,
    ) -> Result<i32, Trap> {
        let public_key = {
            // Public key as serialized bytes
            let source_serialized =
                self.bytes_from_mem(public_key_ptr, PUBLIC_KEY_SIZE + U32_SIZE)?;
            // Public key deserialized
            let source: PublicKey = deserialize(&source_serialized).map_err(Error::BytesRepr)?;
            source
        };
        let weight = Weight::new(weight_value);

        match self.context.update_associated_key(public_key, weight) {
            Ok(_) => Ok(0),
            // This relies on the fact that `UpdateKeyFailure` is represented as
            // i32 and first variant start with number `1`, so all other variants
            // are greater than the first one, so it's safe to assume `0` is success,
            // and any error is greater than 0.
            Err(Error::UpdateKeyFailure(e)) => Ok(e as i32),
            // Any other variant just pass as `Trap`
            Err(e) => Err(e.into()),
        }
    }

    fn set_action_threshold(
        &mut self,
        action_type_value: u32,
        threshold_value: u8,
    ) -> Result<i32, Trap> {
        match ActionType::try_from(action_type_value) {
            Ok(action_type) => {
                let threshold = Weight::new(threshold_value);
                match self.context.set_action_threshold(action_type, threshold) {
                    Ok(_) => Ok(0),
                    Err(Error::SetThresholdFailure(e)) => Ok(e as i32),
                    Err(e) => Err(e.into()),
                }
            }
            Err(_) => Err(Trap::new(TrapKind::Unreachable)),
        }
    }

    /// looks up the public mint contract key in the caller's [uref_lookup] map.
    fn get_mint_contract_public_uref_key(&mut self) -> Result<Key, Error> {
        match self.context.get_uref(MINT_NAME) {
            Some(key @ Key::URef(_)) => Ok(*key),
            _ => Err(URefNotFound(String::from(MINT_NAME))),
        }
    }

    fn get_pos_contract_public_uref_key(&mut self) -> Result<Key, Error> {
        match self.context.get_uref(POS_NAME) {
            Some(key @ Key::URef(_)) => Ok(*key),
            _ => Err(URefNotFound(String::from(POS_NAME))),
        }
    }

    /// looks up the public mint contract key in the caller's [uref_lookup] map and then
    /// gets the "internal" mint contract uref stored under the public mint contract key.
    fn get_mint_contract_uref(&mut self) -> Result<URef, Error> {
        let public_mint_key = self.get_mint_contract_public_uref_key()?;
        let internal_mint_uref = match self.context.read_gs(&public_mint_key)? {
            Some(Value::Key(Key::URef(uref))) => uref,
            _ => return Err(KeyNotFound(public_mint_key)),
        };
        Ok(internal_mint_uref)
    }

    fn get_pos_contract_uref(&mut self) -> Result<URef, Error> {
        let public_pos_key = self.get_pos_contract_public_uref_key()?;
        let internal_mint_uref = match self.context.read_gs(&public_pos_key)? {
            Some(Value::Key(Key::URef(uref))) => uref,
            _ => return Err(KeyNotFound(public_pos_key)),
        };
        Ok(internal_mint_uref)
    }

    /// Calls the "create" method on the mint contract at the given mint contract key
    fn mint_create(&mut self, mint_contract_key: Key) -> Result<PurseId, Error> {
        let args_bytes = {
            let args = "create";
            ArgsParser::parse(&args).and_then(|args| args.to_bytes())?
        };

        let urefs_bytes = Vec::<Key>::new().to_bytes()?;

        self.call_contract(mint_contract_key, args_bytes, urefs_bytes)?;

        let result: URef = deserialize(&self.host_buf)?;

        Ok(PurseId::new(result))
    }

    fn create_purse(&mut self) -> Result<PurseId, Error> {
        let mint_contract_key = Key::URef(self.get_mint_contract_uref()?);
        self.mint_create(mint_contract_key)
    }

    /// Calls the "transfer" method on the mint contract at the given mint contract key
    fn mint_transfer(
        &mut self,
        mint_contract_key: Key,
        source: PurseId,
        target: PurseId,
        amount: U512,
    ) -> Result<(), Error> {
        let source_value: URef = source.value();
        let target_value: URef = target.value();

        let args_bytes = {
            let args = ("transfer", source_value, target_value, amount);
            ArgsParser::parse(&args).and_then(|args| args.to_bytes())?
        };

        let urefs_bytes = vec![Key::URef(source_value), Key::URef(target_value)].to_bytes()?;

        self.call_contract(mint_contract_key, args_bytes, urefs_bytes)?;

        // This will deserialize `host_buf` into the Result type which carries
        // mint contract error.
        let result: Result<(), mint::error::Error> = deserialize(&self.host_buf)?;
        // Wraps mint error into a more general error type through an aggregate
        // system contracts Error.
        Ok(result.map_err(system_contracts::error::Error::from)?)
    }

    /// Creates a new account at a given public key, transferring a given amount of tokens from
    /// the given source purse to the new account's purse.
    fn transfer_to_new_account(
        &mut self,
        source: PurseId,
        target: PublicKey,
        amount: U512,
    ) -> Result<TransferResult, Error> {
        let mint_contract_uref = self.get_mint_contract_uref()?;
        let pos_contract_uref = self.get_pos_contract_uref()?;
        let mint_contract_key = Key::URef(mint_contract_uref);
        let pos_contract_key = Key::URef(pos_contract_uref);
        let target_addr = target.value();
        let target_key = Key::Account(target_addr);

        let target_purse_id = self.mint_create(mint_contract_key)?;

        if source == target_purse_id {
            return Ok(TransferResult::TransferError);
        }

        match self.mint_transfer(mint_contract_key, source, target_purse_id, amount) {
            Ok(_) => {
                let known_urefs = vec![
                    (
                        String::from(MINT_NAME),
                        self.get_mint_contract_public_uref_key()?,
                    ),
                    (
                        String::from(POS_NAME),
                        self.get_pos_contract_public_uref_key()?,
                    ),
                    (pos_contract_uref.as_string(), pos_contract_key),
                    (mint_contract_uref.as_string(), mint_contract_key),
                ]
                .into_iter()
                .map(|(name, key)| {
                    if let Some(uref) = key.as_uref() {
                        (name, Key::URef(URef::new(uref.addr(), AccessRights::READ)))
                    } else {
                        (name, key)
                    }
                })
                .collect();
                let account = Account::create(target_addr, known_urefs, target_purse_id);
                self.context.write_account(target_key, account)?;
                Ok(TransferResult::TransferredToNewAccount)
            }
            Err(_) => Ok(TransferResult::TransferError),
        }
    }

    /// Transferring a given amount of tokens from the given source purse to the new account's
    /// purse. Requires that the [`PurseId`]s have already been created by the mint contract (or
    /// are the genesis account's).
    fn transfer_to_existing_account(
        &mut self,
        source: PurseId,
        target: PurseId,
        amount: U512,
    ) -> Result<TransferResult, Error> {
        let mint_contract_key = Key::URef(self.get_mint_contract_uref()?);

        // This appears to be a load-bearing use of `RuntimeContext::insert_uref`.
        self.context.insert_uref(target.value());

        match self.mint_transfer(mint_contract_key, source, target, amount) {
            Ok(_) => Ok(TransferResult::TransferredToExistingAccount),
            Err(_) => Ok(TransferResult::TransferError),
        }
    }

    /// Transfers `amount` of tokens from default purse of the account to `target` account.
    /// If that account does not exist, creates one.
    fn transfer_to_account(
        &mut self,
        target: PublicKey,
        amount: U512,
    ) -> Result<TransferResult, Error> {
        let source = self.context.account().purse_id();
        self.transfer_from_purse_to_account(source, target, amount)
    }

    /// Transfers `amount` of tokens from `source` purse to `target` account.
    /// If that account does not exist, creates one.
    fn transfer_from_purse_to_account(
        &mut self,
        source: PurseId,
        target: PublicKey,
        amount: U512,
    ) -> Result<TransferResult, Error> {
        let target_key = Key::Account(target.value());
        // Look up the account at the given public key's address
        match self.context.read_account(&target_key)? {
            None => {
                // If no account exists, create a new account and transfer the amount to its purse.
                self.transfer_to_new_account(source, target, amount)
            }
            Some(Value::Account(account)) => {
                let target = account.purse_id_add_only();
                if source == target {
                    return Ok(TransferResult::TransferredToExistingAccount);
                }
                // If an account exists, transfer the amount to its purse
                self.transfer_to_existing_account(source, target, amount)
            }
            Some(_) => {
                // If some other value exists, return an error
                Err(Error::AccountNotFound(target_key))
            }
        }
    }

    /// Transfers `amount` of tokens from `source` purse to `target` purse.
    fn transfer_from_purse_to_purse(
        &mut self,
        source_ptr: u32,
        source_size: u32,
        target_ptr: u32,
        target_size: u32,
        amount_ptr: u32,
        amount_size: u32,
    ) -> Result<PurseTransferResult, Error> {
        let source: PurseId = {
            let bytes = self.bytes_from_mem(source_ptr, source_size as usize)?;
            deserialize(&bytes).map_err(Error::BytesRepr)?
        };

        let target: PurseId = {
            let bytes = self.bytes_from_mem(target_ptr, target_size as usize)?;
            deserialize(&bytes).map_err(Error::BytesRepr)?
        };

        let amount: U512 = {
            let bytes = self.bytes_from_mem(amount_ptr, amount_size as usize)?;
            deserialize(&bytes).map_err(Error::BytesRepr)?
        };

        let mint_contract_key = Key::URef(self.get_mint_contract_uref()?);

        match self.mint_transfer(mint_contract_key, source, target, amount) {
            Ok(_) => Ok(PurseTransferResult::TransferSuccessful),
            Err(_) => Ok(PurseTransferResult::TransferError),
        }
    }

    fn get_balance(&mut self, purse_id: PurseId) -> Result<Option<U512>, Error> {
        let seed = self.get_mint_contract_uref()?.addr();

        let key = purse_id.value().addr().to_bytes()?;

        let uref_key = match self.context.read_ls_with_seed(seed, &key)? {
            Some(Value::Key(uref_key @ Key::URef(_))) => uref_key,
            Some(_) => panic!("expected Value::Key(Key::Uref(_))"),
            None => return Ok(None),
        };

        let ret = match self.context.read_gs_direct(&uref_key)? {
            Some(Value::UInt512(balance)) => Some(balance),
            Some(_) => panic!("expected Value::UInt512(_)"),
            None => None,
        };

        Ok(ret)
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
        let func = FunctionIndex::try_from(index).expect("unknown function index");
        match func {
            FunctionIndex::ReadFuncIndex => {
                // args(0) = pointer to key in Wasm memory
                // args(1) = size of key in Wasm memory
                let (key_ptr, key_size) = Args::parse(args)?;
                let size = self.read(key_ptr, key_size)?;
                Ok(Some(RuntimeValue::I32(size as i32)))
            }

            FunctionIndex::ReadLocalFuncIndex => {
                // args(0) = pointer to key bytes in Wasm memory
                // args(1) = size of key bytes in Wasm memory
                let (key_bytes_ptr, key_bytes_size) = Args::parse(args)?;
                let size = self.read_local(key_bytes_ptr, key_bytes_size)?;
                Ok(Some(RuntimeValue::I32(size as i32)))
            }

            FunctionIndex::SerFnFuncIndex => {
                // args(0) = pointer to name in Wasm memory
                // args(1) = size of name in Wasm memory
                let (name_ptr, name_size) = Args::parse(args)?;
                let size = self.serialize_function(name_ptr, name_size)?;
                Ok(Some(RuntimeValue::I32(size as i32)))
            }

            FunctionIndex::SerKnownURefs => {
                // No args, returns byte size of the known URefs.
                let size = self.serialize_known_urefs()?;
                Ok(Some(RuntimeValue::I32(size as i32)))
            }

            FunctionIndex::WriteFuncIndex => {
                // args(0) = pointer to key in Wasm memory
                // args(1) = size of key
                // args(2) = pointer to value
                // args(3) = size of value
                let (key_ptr, key_size, value_ptr, value_size) = Args::parse(args)?;
                self.write(key_ptr, key_size, value_ptr, value_size)?;
                Ok(None)
            }

            FunctionIndex::WriteLocalFuncIndex => {
                // args(0) = pointer to key in Wasm memory
                // args(1) = size of key
                // args(2) = pointer to value
                // args(3) = size of value
                let (key_bytes_ptr, key_bytes_size, value_ptr, value_size) = Args::parse(args)?;
                self.write_local(key_bytes_ptr, key_bytes_size, value_ptr, value_size)?;
                Ok(None)
            }

            FunctionIndex::AddFuncIndex => {
                // args(0) = pointer to key in Wasm memory
                // args(1) = size of key
                // args(2) = pointer to value
                // args(3) = size of value
                let (key_ptr, key_size, value_ptr, value_size) = Args::parse(args)?;
                self.add(key_ptr, key_size, value_ptr, value_size)?;
                Ok(None)
            }

            FunctionIndex::NewFuncIndex => {
                // args(0) = pointer to key destination in Wasm memory
                // args(1) = pointer to initial value
                // args(2) = size of initial value
                let (key_ptr, value_ptr, value_size) = Args::parse(args)?;
                self.new_uref(key_ptr, value_ptr, value_size)?;
                Ok(None)
            }

            FunctionIndex::GetReadFuncIndex => {
                // args(0) = pointer to destination in Wasm memory
                let dest_ptr = Args::parse(args)?;
                self.set_mem_from_buf(dest_ptr)?;
                Ok(None)
            }

            FunctionIndex::GetFnFuncIndex => {
                // args(0) = pointer to destination in Wasm memory
                let dest_ptr = Args::parse(args)?;
                self.set_mem_from_buf(dest_ptr)?;
                Ok(None)
            }

            FunctionIndex::LoadArgFuncIndex => {
                // args(0) = index of host runtime arg to load
                let i = Args::parse(args)?;
                let size = self.load_arg(i)?;
                Ok(Some(RuntimeValue::I32(size as i32)))
            }

            FunctionIndex::GetArgFuncIndex => {
                // args(0) = pointer to destination in Wasm memory
                let dest_ptr = Args::parse(args)?;
                self.set_mem_from_buf(dest_ptr)?;
                Ok(None)
            }

            FunctionIndex::RetFuncIndex => {
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

            FunctionIndex::CallContractFuncIndex => {
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

            FunctionIndex::GetCallResultFuncIndex => {
                // args(0) = pointer to destination in Wasm memory
                let dest_ptr = Args::parse(args)?;
                self.set_mem_from_buf(dest_ptr)?;
                Ok(None)
            }

            FunctionIndex::GetURefFuncIndex => {
                // args(0) = pointer to uref name in Wasm memory
                // args(1) = size of uref name
                let (name_ptr, name_size) = Args::parse(args)?;
                let size = self.get_uref(name_ptr, name_size)?;
                Ok(Some(RuntimeValue::I32(size as i32)))
            }

            FunctionIndex::HasURefFuncIndex => {
                // args(0) = pointer to uref name in Wasm memory
                // args(1) = size of uref name
                let (name_ptr, name_size) = Args::parse(args)?;
                let result = self.has_uref(name_ptr, name_size)?;
                Ok(Some(RuntimeValue::I32(result)))
            }

            FunctionIndex::AddURefFuncIndex => {
                // args(0) = pointer to uref name in Wasm memory
                // args(1) = size of uref name
                // args(2) = pointer to destination in Wasm memory
                let (name_ptr, name_size, key_ptr, key_size) = Args::parse(args)?;
                self.add_uref(name_ptr, name_size, key_ptr, key_size)?;
                Ok(None)
            }

            FunctionIndex::ListKnownURefsIndex => {
                // args(0) = pointer to destination in Wasm memory
                let ptr = Args::parse(args)?;
                self.list_known_urefs(ptr)?;
                Ok(None)
            }

            FunctionIndex::RemoveURef => {
                // args(0) = pointer to uref name in Wasm memory
                // args(1) = size of uref name
                let (name_ptr, name_size) = Args::parse(args)?;
                self.remove_uref(name_ptr, name_size)?;
                Ok(None)
            }

            FunctionIndex::GetCallerIndex => {
                // args(0) = pointer to Wasm memory where to write.
                let dest_ptr = Args::parse(args)?;
                self.get_caller(dest_ptr)?;
                Ok(None)
            }

            FunctionIndex::GetBlocktimeIndex => {
                // args(0) = pointer to Wasm memory where to write.
                let dest_ptr = Args::parse(args)?;
                self.get_blocktime(dest_ptr)?;
                Ok(None)
            }

            FunctionIndex::GasFuncIndex => {
                let gas: u32 = Args::parse(args)?;
                self.gas(u64::from(gas))?;
                Ok(None)
            }

            FunctionIndex::StoreFnIndex => {
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

            FunctionIndex::ProtocolVersionFuncIndex => {
                Ok(Some(self.context.protocol_version().into()))
            }

            FunctionIndex::IsValidFnIndex => {
                // args(0) = pointer to value to validate
                // args(1) = size of value
                let (value_ptr, value_size) = Args::parse(args)?;

                if self.value_is_valid(value_ptr, value_size)? {
                    Ok(Some(RuntimeValue::I32(1)))
                } else {
                    Ok(Some(RuntimeValue::I32(0)))
                }
            }

            FunctionIndex::RevertFuncIndex => {
                // args(0) = status u32
                let status = Args::parse(args)?;

                Err(self.revert(status))
            }

            FunctionIndex::AddAssociatedKeyFuncIndex => {
                // args(0) = pointer to array of bytes of a public key
                // args(1) = weight of the key
                let (public_key_ptr, weight_value): (u32, u8) = Args::parse(args)?;
                let value = self.add_associated_key(public_key_ptr, weight_value)?;
                Ok(Some(RuntimeValue::I32(value)))
            }

            FunctionIndex::RemoveAssociatedKeyFuncIndex => {
                // args(0) = pointer to array of bytes of a public key
                // args(1) = size of serialized bytes of public key
                let public_key_ptr: u32 = Args::parse(args)?;
                let value = self.remove_associated_key(public_key_ptr)?;
                Ok(Some(RuntimeValue::I32(value)))
            }

            FunctionIndex::UpdateAssociatedKeyFuncIndex => {
                // args(0) = pointer to array of bytes of a public key
                // args(1) = weight of the key
                let (public_key_ptr, weight_value): (u32, u8) = Args::parse(args)?;
                let value = self.update_associated_key(public_key_ptr, weight_value)?;
                Ok(Some(RuntimeValue::I32(value)))
            }

            FunctionIndex::SetActionThresholdFuncIndex => {
                // args(0) = action type
                // args(1) = new threshold
                let (action_type_value, threshold_value): (u32, u8) = Args::parse(args)?;
                let value = self.set_action_threshold(action_type_value, threshold_value)?;
                Ok(Some(RuntimeValue::I32(value)))
            }

            FunctionIndex::CreatePurseIndex => {
                // args(0) = pointer to array for return value
                // args(1) = length of array for return value
                let (dest_ptr, dest_size): (u32, u32) = Args::parse(args)?;
                let purse_id = self.create_purse()?;
                let purse_id_bytes = purse_id.to_bytes().map_err(Error::BytesRepr)?;
                assert_eq!(dest_size, purse_id_bytes.len() as u32);
                self.memory
                    .set(dest_ptr, &purse_id_bytes)
                    .map_err(Error::Interpreter)?;
                Ok(Some(RuntimeValue::I32(0)))
            }

            FunctionIndex::TransferToAccountIndex => {
                // args(0) = pointer to array of bytes of a public key
                // args(1) = length of array of bytes of a public key
                // args(2) = pointer to array of bytes of an amount
                // args(3) = length of array of bytes of an amount
                let (key_ptr, key_size, amount_ptr, amount_size): (u32, u32, u32, u32) =
                    Args::parse(args)?;
                let public_key: PublicKey = {
                    let bytes = self.bytes_from_mem(key_ptr, key_size as usize)?;
                    deserialize(&bytes).map_err(Error::BytesRepr)?
                };
                let amount: U512 = {
                    let bytes = self.bytes_from_mem(amount_ptr, amount_size as usize)?;
                    deserialize(&bytes).map_err(Error::BytesRepr)?
                };
                let ret = self.transfer_to_account(public_key, amount)?;
                Ok(Some(RuntimeValue::I32(ret.into())))
            }

            FunctionIndex::TransferFromPurseToAccountIndex => {
                // args(0) = pointer to array of bytes in Wasm memory of a source purse
                // args(1) = length of array of bytes in Wasm memory of a source purse
                // args(2) = pointer to array of bytes in Wasm memory of a public key
                // args(3) = length of array of bytes in Wasm memory of a public key
                // args(4) = pointer to array of bytes in Wasm memory of an amount
                // args(5) = length of array of bytes in Wasm memory of an amount
                let (source_ptr, source_size, key_ptr, key_size, amount_ptr, amount_size): (
                    u32,
                    u32,
                    u32,
                    u32,
                    u32,
                    u32,
                ) = Args::parse(args)?;

                let source_purse = {
                    let bytes = self.bytes_from_mem(source_ptr, source_size as usize)?;
                    deserialize(&bytes).map_err(Error::BytesRepr)?
                };
                let public_key: PublicKey = {
                    let bytes = self.bytes_from_mem(key_ptr, key_size as usize)?;
                    deserialize(&bytes).map_err(Error::BytesRepr)?
                };
                let amount: U512 = {
                    let bytes = self.bytes_from_mem(amount_ptr, amount_size as usize)?;
                    deserialize(&bytes).map_err(Error::BytesRepr)?
                };
                let ret = self.transfer_from_purse_to_account(source_purse, public_key, amount)?;
                Ok(Some(RuntimeValue::I32(ret.into())))
            }

            FunctionIndex::TransferFromPurseToPurseIndex => {
                // args(0) = pointer to array of bytes in Wasm memory of a source purse
                // args(1) = length of array of bytes in Wasm memory of a source purse
                // args(2) = pointer to array of bytes in Wasm memory of a target purse
                // args(3) = length of array of bytes in Wasm memory of a target purse
                // args(4) = pointer to array of bytes in Wasm memory of an amount
                // args(5) = length of array of bytes in Wasm memory of an amount
                let (source_ptr, source_size, target_ptr, target_size, amount_ptr, amount_size) =
                    Args::parse(args)?;
                let ret = self.transfer_from_purse_to_purse(
                    source_ptr,
                    source_size,
                    target_ptr,
                    target_size,
                    amount_ptr,
                    amount_size,
                )?;
                Ok(Some(RuntimeValue::I32(ret.into())))
            }

            FunctionIndex::GetBalanceIndex => {
                // args(0) = pointer to purse_id input
                // args(1) = length of purse_id
                let (ptr, ptr_size): (u32, u32) = Args::parse(args)?;

                let purse_id: PurseId = {
                    let bytes = self.bytes_from_mem(ptr, ptr_size as usize)?;
                    deserialize(&bytes).map_err(Error::BytesRepr)?
                };

                let ret = match self.get_balance(purse_id)? {
                    Some(balance) => {
                        let balance_bytes = balance.to_bytes().map_err(Error::BytesRepr)?;
                        self.host_buf = balance_bytes;
                        self.host_buf.len() as i32
                    }
                    None => 0i32,
                };

                Ok(Some(RuntimeValue::I32(ret)))
            }
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

    let known_urefs = extract_access_rights_from_keys(refs.values().cloned().chain(extra_urefs));

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
            current_runtime.context.authorization_keys().clone(),
            &current_runtime.context.account(),
            key,
            current_runtime.context.get_blocktime(),
            current_runtime.context.gas_limit(),
            current_runtime.context.gas_counter(),
            current_runtime.context.fn_store_id(),
            current_runtime.context.rng(),
            protocol_version,
            current_runtime.context.correlation_id(),
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
                            extract_access_rights_from_urefs(ret_urefs.clone());
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

/// Groups a collection of urefs by their addresses and accumulates access rights per key
pub fn extract_access_rights_from_urefs<I: IntoIterator<Item = URef>>(
    input: I,
) -> HashMap<URefAddr, HashSet<AccessRights>> {
    input
        .into_iter()
        .map(|uref: URef| (uref.addr(), uref.access_rights()))
        .group_by(|(key, _)| *key)
        .into_iter()
        .map(|(key, group)| {
            (
                key,
                group
                    .filter_map(|(_, x)| x)
                    .collect::<HashSet<AccessRights>>(),
            )
        })
        .collect()
}

/// Groups a collection of keys by their address and accumulates access rights per key.
pub fn extract_access_rights_from_keys<I: IntoIterator<Item = Key>>(
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
                group
                    .filter_map(|(_, x)| x)
                    .collect::<HashSet<AccessRights>>(),
            )
        })
        .collect()
}

pub fn create_rng(account_addr: [u8; 32], nonce: u64) -> ChaChaRng {
    let mut seed: [u8; 32] = [0u8; 32];
    let mut data: Vec<u8> = Vec::new();
    let mut hasher = VarBlake2b::new(32).unwrap();
    data.extend(&account_addr);
    data.extend_from_slice(&nonce.to_le_bytes());
    hasher.input(data);
    hasher.variable_result(|hash| seed.clone_from_slice(hash));
    ChaChaRng::from_seed(seed)
}

#[macro_export]
macro_rules! on_fail_charge {
    ($fn:expr) => {
        match $fn {
            Ok(res) => res,
            Err(e) => {
                let exec_err: ::execution::Error = e.into();
                return ExecutionResult::precondition_failure(exec_err.into());
            }
        }
    };
    ($fn:expr, $cost:expr) => {
        match $fn {
            Ok(res) => res,
            Err(e) => {
                let exec_err: ::execution::Error = e.into();
                return ExecutionResult::Failure {
                    error: exec_err.into(),
                    effect: Default::default(),
                    cost: $cost,
                };
            }
        }
    };
    ($fn:expr, $cost:expr, $effect:expr) => {
        match $fn {
            Ok(res) => res,
            Err(e) => {
                let exec_err: ::execution::Error = e.into();
                return ExecutionResult::Failure {
                    error: exec_err.into(),
                    effect: $effect,
                    cost: $cost,
                };
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
        base_key: Key,
        account: &Account,
        authorized_keys: BTreeSet<PublicKey>,
        blocktime: BlockTime,
        gas_limit: u64,
        protocol_version: u64,
        correlation_id: CorrelationId,
        tc: Rc<RefCell<TrackingCopy<R>>>,
    ) -> ExecutionResult
    where
        R::Error: Into<Error>;

    #[allow(clippy::too_many_arguments)]
    fn exec_direct<R: StateReader<Key, Value>>(
        &self,
        parity_module: A,
        args: &[u8],
        keys: &mut BTreeMap<String, Key>,
        base_key: Key,
        account: &Account,
        authorization_keys: BTreeSet<PublicKey>,
        blocktime: BlockTime,
        gas_limit: u64,
        protocol_version: u64,
        correlation_id: CorrelationId,
        state: Rc<RefCell<TrackingCopy<R>>>,
    ) -> ExecutionResult
    where
        R::Error: Into<Error>;
}

pub struct WasmiExecutor;

impl Executor<Module> for WasmiExecutor {
    fn exec<R: StateReader<Key, Value>>(
        &self,
        parity_module: Module,
        args: &[u8],
        base_key: Key,
        account: &Account,
        authorized_keys: BTreeSet<PublicKey>,
        blocktime: BlockTime,
        gas_limit: u64,
        protocol_version: u64,
        correlation_id: CorrelationId,
        tc: Rc<RefCell<TrackingCopy<R>>>,
    ) -> ExecutionResult
    where
        R::Error: Into<Error>,
    {
        let (instance, memory) =
            on_fail_charge!(instance_and_memory(parity_module.clone(), protocol_version));

        let mut uref_lookup_local = account.urefs_lookup().clone();
        let known_urefs: HashMap<URefAddr, HashSet<AccessRights>> =
            extract_access_rights_from_keys(uref_lookup_local.values().cloned());
        let account_bytes = base_key.as_account().unwrap();
        let rng = create_rng(account_bytes, account.nonce());
        let gas_counter = 0u64;
        let fn_store_id = 0u32;

        // Snapshot of effects before execution, so in case of error
        // only nonce update can be returned.
        let effects_snapshot = tc.borrow().effect();

        let arguments: Vec<Vec<u8>> = if args.is_empty() {
            Vec::new()
        } else {
            // TODO: figure out how this works with the cost model
            // https://casperlabs.atlassian.net/browse/EE-239
            on_fail_charge!(deserialize(args), args.len() as u64, effects_snapshot)
        };

        let context = RuntimeContext::new(
            tc,
            &mut uref_lookup_local,
            known_urefs,
            arguments,
            authorized_keys,
            &account,
            base_key,
            blocktime,
            gas_limit,
            gas_counter,
            fn_store_id,
            Rc::new(RefCell::new(rng)),
            protocol_version,
            correlation_id,
        );

        let mut runtime = Runtime::new(memory, parity_module, context);
        on_fail_charge!(
            instance.invoke_export("call", &[], &mut runtime),
            runtime.context.gas_counter(),
            effects_snapshot
        );

        ExecutionResult::Success {
            effect: runtime.context.effect(),
            cost: runtime.context.gas_counter(),
        }
    }

    fn exec_direct<R: StateReader<Key, Value>>(
        &self,
        parity_module: Module,
        args: &[u8],
        keys: &mut BTreeMap<String, Key>,
        base_key: Key,
        account: &Account,
        authorization_keys: BTreeSet<PublicKey>,
        blocktime: BlockTime,
        gas_limit: u64,
        protocol_version: u64,
        correlation_id: CorrelationId,
        state: Rc<RefCell<TrackingCopy<R>>>,
    ) -> ExecutionResult
    where
        R::Error: Into<Error>,
    {
        let mut uref_lookup = keys.clone();
        let known_urefs: HashMap<URefAddr, HashSet<AccessRights>> =
            extract_access_rights_from_keys(uref_lookup.values().cloned());

        //let base_key = Key::Account(account.pub_key());
        let rng = {
            let rng = create_rng(account.pub_key(), account.nonce());
            Rc::new(RefCell::new(rng))
        };
        let gas_counter = 0u64; // maybe const?
        let fn_store_id = 0u32; // maybe const?

        // Snapshot of effects before execution, so in case of error only nonce update can be returned.
        let effects_snapshot = state.borrow().effect();

        let args: Vec<Vec<u8>> = if args.is_empty() {
            Vec::new()
        } else {
            on_fail_charge!(deserialize(args), args.len() as u64, effects_snapshot)
        };

        let context = RuntimeContext::new(
            state,
            &mut uref_lookup,
            known_urefs,
            args,
            authorization_keys,
            &account,
            base_key,
            blocktime,
            gas_limit,
            gas_counter,
            fn_store_id,
            rng,
            protocol_version,
            correlation_id,
        );

        let (instance, memory) =
            on_fail_charge!(instance_and_memory(parity_module.clone(), protocol_version));

        let mut runtime = Runtime::new(memory, parity_module, context);

        match instance.invoke_export("call", &[], &mut runtime) {
            Ok(_) => ExecutionResult::Success {
                effect: runtime.context.effect(),
                cost: runtime.context.gas_counter(),
            },
            Err(e) => {
                if let Some(host_error) = e.as_host_error() {
                    // `ret` Trap is a success; downcast and attempt to extract result
                    let downcasted_error = host_error.downcast_ref::<Error>().unwrap();
                    match downcasted_error {
                        Error::Ret(ref _ret_urefs) => {
                            // NOTE: currently, ExecutionResult does not include runtime.result or extra urefs
                            //  and thus we cannot get back a value from the executed contract...
                            // TODO?: add ability to include extra_urefs and runtime.result to ExecutionResult::Success

                            return ExecutionResult::Success {
                                effect: runtime.context.effect(),
                                cost: runtime.context.gas_counter(),
                            };
                        }
                        Error::Revert(status) => {
                            // Propagate revert as revert, instead of passing it as
                            // InterpreterError.
                            return ExecutionResult::Failure {
                                error: Error::Revert(*status).into(),
                                effect: effects_snapshot,
                                cost: runtime.context.gas_counter(),
                            };
                        }
                        _ => {}
                    }
                }

                ExecutionResult::Failure {
                    error: Error::Interpreter(e).into(),
                    effect: effects_snapshot,
                    cost: runtime.context.gas_counter(),
                }
            }
        }
    }
}

/// Turns `key` into a `([u8; 32], AccessRights)` tuple.
/// Returns None if `key` is not `Key::URef` as it wouldn't have `AccessRights` associated with it.
/// Helper function for creating `known_urefs` associating addresses and corresponding `AccessRights`.
pub fn key_to_tuple(key: Key) -> Option<([u8; 32], Option<AccessRights>)> {
    match key {
        Key::URef(uref) => Some((uref.addr(), uref.access_rights())),
        Key::Account(_) => None,
        Key::Hash(_) => None,
        Key::Local { .. } => None,
    }
}

#[cfg(test)]
mod tests {
    use rand::RngCore;
    use rand_chacha::ChaChaRng;

    use engine_state::execution_result::ExecutionResult;
    use execution::create_rng;

    use super::Error;

    fn on_fail_charge_test_helper<T>(
        f: impl Fn() -> Result<T, Error>,
        success_cost: u64,
        error_cost: u64,
    ) -> ExecutionResult {
        let _result = on_fail_charge!(f(), error_cost);
        ExecutionResult::Success {
            effect: Default::default(),
            cost: success_cost,
        }
    }

    #[test]
    fn on_fail_charge_ok_test() {
        match on_fail_charge_test_helper(|| Ok(()), 123, 456) {
            ExecutionResult::Success { cost, .. } => assert_eq!(cost, 123),
            ExecutionResult::Failure { .. } => panic!("Should be success"),
        }
    }
    #[test]
    fn on_fail_charge_err_laziness_test() {
        match on_fail_charge_test_helper(|| Err(Error::GasLimit) as Result<(), _>, 123, 456) {
            ExecutionResult::Success { .. } => panic!("Should fail"),
            ExecutionResult::Failure { cost, .. } => assert_eq!(cost, 456),
        }
    }
    #[test]
    fn on_fail_charge_with_action() {
        use contract_ffi::key::Key;
        use engine_shared::transform::Transform;
        use engine_state::execution_effect::ExecutionEffect;
        use engine_state::op::Op;
        let f = || {
            let input: Result<(), Error> = Err(Error::GasLimit);
            on_fail_charge!(input, 456, {
                let mut effect = ExecutionEffect::default();

                effect.ops.insert(Key::Hash([42u8; 32]), Op::Read);
                effect
                    .transforms
                    .insert(Key::Hash([42u8; 32]), Transform::Identity);

                effect
            });
            ExecutionResult::Success {
                effect: Default::default(),
                cost: 0,
            }
        };
        match f() {
            ExecutionResult::Success { .. } => panic!("Should fail"),
            ExecutionResult::Failure { cost, effect, .. } => {
                assert_eq!(cost, 456);
                // Check if the containers are non-empty
                assert_eq!(effect.ops.len(), 1);
                assert_eq!(effect.transforms.len(), 1);
            }
        }
    }

    fn gen_random(rng: &mut ChaChaRng) -> [u8; 32] {
        let mut buff = [0u8; 32];
        rng.fill_bytes(&mut buff);
        buff
    }

    #[test]
    fn should_generate_different_numbers_for_different_seeds() {
        let account_addr = [0u8; 32];
        let mut rng_a = create_rng(account_addr, 1);
        let mut rng_b = create_rng(account_addr, 2);
        let random_a = gen_random(&mut rng_a);
        let random_b = gen_random(&mut rng_b);

        assert_ne!(random_a, random_b)
    }

    #[test]
    fn should_generate_same_numbers_for_same_seed() {
        let account_addr = [0u8; 32];
        let mut rng_a = create_rng(account_addr, 1);
        let mut rng_b = create_rng(account_addr, 1);
        let random_a = gen_random(&mut rng_a);
        let random_b = gen_random(&mut rng_b);

        assert_eq!(random_a, random_b)
    }
}
