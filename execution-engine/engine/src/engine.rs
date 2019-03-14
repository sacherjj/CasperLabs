use common::key::Key;
use execution::{Error as ExecutionError, Executor};
use parking_lot::Mutex;
use shared::newtypes::Blake2bHash;
use std::collections::HashMap;
use std::marker::PhantomData;
use storage::error::{GlobalStateError, RootNotFound};
use storage::gs::{DbReader, ExecutionEffect, TrackingCopy};
use storage::history::*;
use storage::transform::Transform;
use vm::wasm_costs::WasmCosts;
use wasm_prep::Preprocessor;

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

pub struct ExecutionResult {
    pub result: Result<ExecutionEffect, Error>,
    pub cost: u64,
}

impl ExecutionResult {
    pub fn failure(error: Error, cost: u64) -> ExecutionResult {
        ExecutionResult {
            result: Err(error),
            cost,
        }
    }

    pub fn success(effect: ExecutionEffect, cost: u64) -> ExecutionResult {
        ExecutionResult {
            result: Ok(effect),
            cost,
        }
    }
}

#[derive(Debug)]
pub enum Error {
    PreprocessingError(String),
    ExecError(ExecutionError),
    StorageError(GlobalStateError),
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

impl From<GlobalStateError> for Error {
    fn from(error: GlobalStateError) -> Self {
        Error::StorageError(error)
    }
}

impl From<ExecutionError> for Error {
    fn from(error: ExecutionError) -> Self {
        Error::ExecError(error)
    }
}

impl<H, R> EngineState<R, H>
where
    H: History<R>,
    R: DbReader,
    H::Error: Into<Error>,
{
    pub fn new(state: H) -> EngineState<R, H> {
        EngineState {
            state: Mutex::new(state),
            wasm_costs: WasmCosts::new(),
            _phantom: PhantomData,
        }
    }

    pub fn tracking_copy(&self, hash: Blake2bHash) -> Result<Option<TrackingCopy<R>>, Error> {
        self.state.lock().checkout(hash).map_err(Into::into)
    }

    // TODO run_deploy should perform preprocessing and validation of the deploy.
    // It should validate the signatures, ocaps etc.
    #[allow(clippy::too_many_arguments)]
    pub fn run_deploy<A, P: Preprocessor<A>, E: Executor<A>>(
        &self,
        module_bytes: &[u8],
        address: [u8; 20],
        timestamp: u64,
        nonce: u64,
        prestate_hash: Blake2bHash,
        gas_limit: u64,
        executor: &E,
        preprocessor: &P,
    ) -> Result<ExecutionResult, RootNotFound> {
        match preprocessor.preprocess(module_bytes, &self.wasm_costs) {
            Err(error) => Ok(ExecutionResult::failure(error.into(), 0)),
            Ok(module) => match self.state.lock().checkout(prestate_hash) {
                Err(error) => Ok(ExecutionResult::failure(error.into(), 0)),
                Ok(checkout_result) => match checkout_result {
                    None => Err(RootNotFound(prestate_hash)),
                    Some(mut tc) => {
                        match executor.exec(module, address, timestamp, nonce, gas_limit, &mut tc) {
                            (Ok(ee), cost) => Ok(ExecutionResult::success(ee, cost)),
                            (Err(error), cost) => Ok(ExecutionResult::failure(error.into(), cost)),
                        }
                    }
                },
            },
        }
    }

    pub fn apply_effect(
        &self,
        prestate_hash: Blake2bHash,
        effects: HashMap<Key, Transform>,
    ) -> Result<Option<Blake2bHash>, H::Error> {
        self.state.lock().commit(prestate_hash, effects)
    }
}
