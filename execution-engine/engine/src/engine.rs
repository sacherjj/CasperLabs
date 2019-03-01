use common::key::Key;
use execution::{exec, Error as ExecutionError};
use parking_lot::Mutex;
use std::collections::HashMap;
use std::marker::PhantomData;
use storage::error::{Error as StorageError, RootNotFound};
use storage::gs::{DbReader, ExecutionEffect, TrackingCopy};
use storage::history::*;
use storage::transform::Transform;
use vm::wasm_costs::WasmCosts;
use wasm_prep::process;

pub struct EngineState<R, H>
where
    R: DbReader,
    H: History<R>,
{
    // Tracks the "state" of the blockchain (or is an interface to it).
    // I think it should be constrained with a lifetime parameter.
    state: Mutex<H>,
    wasm_costs: WasmCosts,
    _phantom: PhantomData<R>,
}

pub enum ExecutionResult {
    Success(ExecutionEffect),
    Failure(Error),
}

#[derive(Debug)]
pub enum Error {
    PreprocessingError(String),
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
    R: DbReader,
{
    pub fn new(state: G) -> EngineState<R, G> {
        EngineState {
            state: Mutex::new(state),
            wasm_costs: WasmCosts::new(),
            _phantom: PhantomData,
        }
    }

    pub fn tracking_copy(&self, hash: [u8; 32]) -> Result<TrackingCopy<R>, RootNotFound> {
        self.state.lock().checkout(hash)
    }

    //TODO run_deploy should perform preprocessing and validation of the deploy.
    //It should validate the signatures, ocaps etc.
    pub fn run_deploy(
        &self,
        module_bytes: &[u8],
        address: [u8; 20],
        timestamp: u64,
        nonce: u64,
        prestate_hash: [u8; 32],
        gas_limit: u64,
    ) -> Result<ExecutionResult, RootNotFound> {
        match process(module_bytes, &self.wasm_costs) {
            Err(error) => Ok(ExecutionResult::Failure(error.into())),
            Ok(module) => {
                let mut tc: storage::gs::TrackingCopy<R> =
                    self.state.lock().checkout(prestate_hash)?;
                match exec(module, address, timestamp, nonce, gas_limit, &mut tc) {
                    Ok(ee) => Ok(ExecutionResult::Success(ee)),
                    Err(error) => Ok(ExecutionResult::Failure(error.into())),
                }
            }
        }
    }

    pub fn apply_effect(
        &self,
        prestate_hash: [u8; 32],
        effects: HashMap<Key, Transform>,
    ) -> Result<CommitResult, RootNotFound> {
        self.state.lock().commit(prestate_hash, effects)
    }
}
