use common::key::Key;
use execution::{self, Executor};
use parking_lot::Mutex;
use shared::newtypes::Blake2bHash;
use shared::transform::Transform;
use std::cell::RefCell;
use std::collections::HashMap;
use std::rc::Rc;
use storage::global_state::{CommitResult, History};
use tracking_copy::TrackingCopy;
use wasm_prep::Preprocessor;

pub mod error;
pub mod execution_effect;
pub mod execution_result;
pub mod op;

use self::error::{Error, RootNotFound};
use self::execution_result::ExecutionResult;

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
