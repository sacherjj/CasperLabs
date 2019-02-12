use common::key::Key;
use common::value;
use execution::{exec, Error as ExecutionError};
use parity_wasm::elements::Module;
use parking_lot::Mutex;
use std::collections::BTreeMap;
use storage::error::Error as StorageError;
use storage::gs::{ExecutionEffect, DbReader};
use storage::history::*;
use storage::transform::Transform;
use vm::wasm_costs::WasmCosts;
use wasm_prep::process;
use std::marker::PhantomData;
use std::collections::HashMap;

pub struct EngineState<R, H>
    where R: DbReader,
          H: History<R> {
    // Tracks the "state" of the blockchain (or is an interface to it).
    // I think it should be constrained with a lifetime parameter.
    state: Mutex<H>,
    wasm_costs: WasmCosts,
    _phantom: PhantomData<R>,
}

#[derive(Debug)]
pub enum Error {
    PreprocessingError(String),
    SignatureError(String),
    ExecError(ExecutionError),
    StorageError(StorageError),
}

impl From<wasm_prep::PreprocessingError> for Error {
    fn from(error: wasm_prep::PreprocessingError) -> Self {
        match error {
            wasm_prep::PreprocessingError::InvalidImportsError(error) => {
                Error::PreprocessingError(error)
            }
            wasm_prep::PreprocessingError::NoExportSection => {
                Error::PreprocessingError(String::from("No export section found."))
            }
            wasm_prep::PreprocessingError::NoImportSection => {
                Error::PreprocessingError(String::from("No import section found,"))
            }
            wasm_prep::PreprocessingError::DeserializeError(error) => {
                Error::PreprocessingError(error)
            }
            wasm_prep::PreprocessingError::OperationForbiddenByGasRules => {
                Error::PreprocessingError(String::from("Encountered operation forbidden by gas rules. Consult instruction -> metering config map."))
            }
            wasm_prep::PreprocessingError::StackLimiterError => {
                Error::PreprocessingError(String::from("Wasm contract error: Stack limiter error."))

            }
        }
    }
}

impl From<StorageError> for Error {
    fn from(error: StorageError) -> Self {
        Error::StorageError(error)
    }
}

impl From<ExecutionError> for Error {
    fn from(error: ExecutionError) -> Self {
        Error::ExecError(error)
    }
}

impl<G, R> EngineState<R, G>
where
    G: History<R>,
    R: DbReader
{
    // To run, contracts need an existing account.
    // This function puts artificial entry in the GlobalState.
    pub fn with_mocked_account(&self, account_addr: [u8; 20]) {
        let transformations = {
            let account = value::Account::new([48u8; 32], 0, BTreeMap::new());
            let transform = Transform::Write(value::Value::Acct(account));
            let mut tf_map = HashMap::new();
            tf_map.insert(Key::Account(account_addr), transform);
            ExecutionEffect(HashMap::new(), tf_map)
        };
        self.state
            .lock()
            .commit(transformations)
            .expect("Creation of mocked account should be a success.");
    }

    pub fn new(state: G) -> EngineState<R, G> {
        EngineState {
            state: Mutex::new(state),
            wasm_costs: WasmCosts::new(),
            _phantom: PhantomData
        }
    }

    //TODO run_deploy should perform preprocessing and validation of the deploy.
    //It should validate the signatures, ocaps etc.
    pub fn run_deploy(
        &self,
        module_bytes: &[u8],
        address: [u8; 20],
        poststate_hash: [u8; 32],
        gas_limit: &u64,
    ) -> Result<ExecutionEffect, Error> {
        let module = self.preprocess_module(module_bytes, &self.wasm_costs)?;
        exec(module, address, poststate_hash, &gas_limit, &*self.state.lock()).map_err(|e| e.into())
    }

    //TODO: apply_effect shouldn't accept single tuples and return post state hash.
    //Instead it should accept a sequence of transformations made in the block.
    pub fn apply_effect(&self, block_hash: [u8;32], key: Key, eff: Transform) -> Result<(), Error> {
        let effects = {
            let mut m = HashMap::new();
            m.insert(key, eff);
            ExecutionEffect(HashMap::new(), m)
        };
        self.state.lock()
            .commit(block_hash, effects)
            .map(|_| ())
            .map_err(|err| err.into())
    }

    //TODO: inject gas counter, limit stack size etc
    fn preprocess_module(
        &self,
        module_bytes: &[u8],
        wasm_costs: &WasmCosts,
    ) -> Result<Module, Error> {
        process(module_bytes, wasm_costs).map_err(|err| err.into())
    }

    //TODO return proper error
    pub fn validate_signatures(
        &self,
        _deploy: &[u8],
        _signature: &[u8],
        _signature_alg: &str,
    ) -> Result<String, Error> {
        Ok(String::from("OK"))
    }
}
