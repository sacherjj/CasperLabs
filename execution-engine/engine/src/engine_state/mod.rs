pub mod error;
pub mod execution_effect;
pub mod execution_result;
pub mod op;

use std::cell::RefCell;
use std::collections::HashMap;
use std::rc::Rc;

use parking_lot::Mutex;

use common::key::Key;
use shared::newtypes::{Blake2bHash, CorrelationId};
use shared::transform::Transform;
use storage::global_state::{CommitResult, History};
use wasm_prep::Preprocessor;

use self::error::{Error, RootNotFound};
use self::execution_result::ExecutionResult;
use execution::{self, Executor};
use tracking_copy::TrackingCopy;

pub struct EngineState<H> {
    // Tracks the "state" of the blockchain (or is an interface to it).
    // I think it should be constrained with a lifetime parameter.
    state: Mutex<H>,
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
        correlation_id: CorrelationId,
        executor: &E,
        preprocessor: &P,
    ) -> Result<ExecutionResult, RootNotFound> {
        let module = match preprocessor.preprocess(module_bytes) {
            Err(error) => return Ok(ExecutionResult::precondition_failure(error.into())),
            Ok(module) => module,
        };
        let checkout_result = match self.tracking_copy(prestate_hash) {
            Err(error) => return Ok(ExecutionResult::precondition_failure(error)),
            Ok(checkout_result) => checkout_result,
        };
        let tracking_copy = match checkout_result {
            None => return Err(RootNotFound(prestate_hash)),
            Some(mut tracking_copy) => Rc::new(RefCell::new(tracking_copy)),
        };
        Ok(executor.exec(
            module,
            args,
            address,
            timestamp,
            nonce,
            gas_limit,
            protocol_version,
            correlation_id,
            tracking_copy,
        ))
    }

    pub fn apply_effect(
        &self,
        correlation_id: CorrelationId,
        prestate_hash: Blake2bHash,
        effects: HashMap<Key, Transform>,
    ) -> Result<CommitResult, H::Error> {
        self.state
            .lock()
            .commit(correlation_id, prestate_hash, effects)
    }
}
