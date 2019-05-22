use common::key::Key;
use execution::{self, Executor};
use failure::Fail;
use parking_lot::Mutex;
use shared::newtypes::Blake2bHash;
use shared::transform::Transform;
use std::cell::RefCell;
use std::collections::HashMap;
use std::rc::Rc;
use storage::global_state::{CommitResult, History};
use tracking_copy::TrackingCopy;
use wasm_prep::Preprocessor;

#[derive(Debug, PartialEq, Eq, Clone)]
pub struct RootNotFound(pub Blake2bHash);

pub struct EngineState<H> {
    // Tracks the "state" of the blockchain (or is an interface to it).
    // I think it should be constrained with a lifetime parameter.
    state: Mutex<H>,
}

#[derive(PartialEq, Eq, Debug, Clone)]
pub enum Op {
    Read,
    Write,
    Add,
    NoOp,
}

impl std::ops::Add for Op {
    type Output = Op;

    fn add(self, other: Op) -> Op {
        match (self, other) {
            (a, Op::NoOp) => a,
            (Op::NoOp, b) => b,
            (Op::Read, Op::Read) => Op::Read,
            (Op::Add, Op::Add) => Op::Add,
            _ => Op::Write,
        }
    }
}

impl std::fmt::Display for Op {
    fn fmt(&self, f: &mut std::fmt::Formatter) -> std::fmt::Result {
        write!(f, "{:?}", self)
    }
}

#[derive(Debug)]
pub struct ExecutionEffect(pub HashMap<Key, Op>, pub HashMap<Key, Transform>);

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

#[derive(Fail, Debug)]
pub enum Error {
    #[fail(display = "{}", _0)]
    PreprocessingError(String),
    #[fail(display = "Execution error")]
    ExecError(::execution::Error),
    #[fail(display = "Storage error")]
    StorageError(storage::error::Error),
    #[fail(display = "Unreachable")]
    Unreachable,
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

impl From<storage::error::Error> for Error {
    fn from(error: storage::error::Error) -> Self {
        Error::StorageError(error)
    }
}

impl From<::execution::Error> for Error {
    fn from(error: ::execution::Error) -> Self {
        Error::ExecError(error)
    }
}

impl From<!> for Error {
    fn from(_error: !) -> Self {
        Error::Unreachable
    }
}

impl<H> EngineState<H>
where
    H: History,
    H::Error: Into<execution::Error>,
{
    pub fn new(state: H) -> EngineState<H> {
        EngineState {
            state: Mutex::new(state),
        }
    }

    pub fn tracking_copy(
        &self,
        hash: Blake2bHash,
    ) -> Result<Option<TrackingCopy<H::Reader>>, Error> {
        match self.state.lock().checkout(hash).map_err(Into::into)? {
            Some(tc) => Ok(Some(TrackingCopy::new(tc))),
            None => Ok(None),
        }
    }

    // TODO run_deploy should perform preprocessing and validation of the deploy.
    // It should validate the signatures, ocaps etc.
    #[allow(clippy::too_many_arguments)]
    pub fn run_deploy<A, P: Preprocessor<A>, E: Executor<A>>(
        &self,
        module_bytes: &[u8],
        args: &[u8],
        address: [u8; 32],
        timestamp: u64,
        nonce: u64,
        prestate_hash: Blake2bHash,
        gas_limit: u64,
        protocol_version: u64,
        executor: &E,
        preprocessor: &P,
    ) -> Result<ExecutionResult, RootNotFound> {
        match preprocessor.preprocess(module_bytes) {
            Err(error) => Ok(ExecutionResult::failure(error.into(), 0)),
            Ok(module) => match self.tracking_copy(prestate_hash) {
                Err(error) => Ok(ExecutionResult::failure(error, 0)),
                Ok(checkout_result) => match checkout_result {
                    None => Err(RootNotFound(prestate_hash)),
                    Some(mut tc) => {
                        let rc_tc = Rc::new(RefCell::new(tc));
                        match executor.exec(
                            module,
                            args,
                            address,
                            timestamp,
                            nonce,
                            gas_limit,
                            protocol_version,
                            rc_tc,
                        ) {
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
    ) -> Result<CommitResult, H::Error> {
        self.state.lock().commit(prestate_hash, effects)
    }
}
