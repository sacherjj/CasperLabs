use std::cell::RefCell;
use std::collections::HashMap;
use std::rc::Rc;
use std::sync::Arc;

use parking_lot::Mutex;

use common::key::Key;
use common::value::account::{BlockTime, PublicKey};
use common::value::{Value, U512};
use engine_state::utils::WasmiBytes;
use execution::{self, Executor};
use shared::newtypes::{Blake2bHash, CorrelationId};
use shared::transform::Transform;
use storage::global_state::{CommitResult, History, StateReader};
use tracking_copy::TrackingCopy;
use wasm_prep::wasm_costs::WasmCosts;
use wasm_prep::Preprocessor;

use self::error::{Error, RootNotFound};
use self::execution_result::ExecutionResult;
use self::genesis::{create_genesis_effects, GenesisResult};

pub mod error;
pub mod execution_effect;
pub mod execution_result;
pub mod genesis;
pub mod op;
pub mod utils;

pub struct EngineState<H> {
    // Tracks the "state" of the blockchain (or is an interface to it).
    // I think it should be constrained with a lifetime parameter.
    state: Arc<Mutex<H>>,
}

impl<H> EngineState<H>
where
    H: History,
    H::Error: Into<execution::Error>,
{
    pub fn new(state: H) -> EngineState<H> {
        let state = Arc::new(Mutex::new(state));
        EngineState { state }
    }

    #[allow(clippy::too_many_arguments)]
    pub fn commit_genesis(
        &self,
        correlation_id: CorrelationId,
        genesis_account_addr: [u8; 32],
        initial_tokens: U512,
        mint_code_bytes: &[u8],
        proof_of_stake_code_bytes: &[u8],
        genesis_validators: Vec<(PublicKey, U512)>,
        protocol_version: u64,
    ) -> Result<GenesisResult, Error> {
        let mint_code = WasmiBytes::new(mint_code_bytes, WasmCosts::free())?;
        let pos_code = WasmiBytes::new(proof_of_stake_code_bytes, WasmCosts::free())?;

        let effects = create_genesis_effects(
            genesis_account_addr,
            initial_tokens,
            mint_code,
            pos_code,
            genesis_validators,
            protocol_version,
        )?;
        let mut state_guard = self.state.lock();
        let prestate_hash = state_guard.empty_root();
        let commit_result = state_guard
            .commit(correlation_id, prestate_hash, effects.transforms.to_owned())
            .map_err(Into::into)?;

        let genesis_result = GenesisResult::from_commit_result(commit_result, effects);

        Ok(genesis_result)
    }

    pub fn state(&self) -> Arc<Mutex<H>> {
        Arc::clone(&self.state)
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
        address: Key,
        authorized_keys: Vec<PublicKey>,
        blocktime: BlockTime,
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
            authorized_keys,
            blocktime,
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

pub enum GetBondedValidatorsError<H: History> {
    StorageErrors(H::Error),
    PostStateHashNotFound(Blake2bHash),
    PoSNotFound(Key),
}

/// Calculates bonded validators at `root_hash` state.
pub fn get_bonded_validators<H: History>(
    state: Arc<Mutex<H>>,
    root_hash: Blake2bHash,
    pos_key: &Key, // Address of the PoS as currently bonded validators are stored in its known urefs map.
    correlation_id: CorrelationId,
) -> Result<HashMap<PublicKey, U512>, GetBondedValidatorsError<H>> {
    state
        .lock()
        .checkout(root_hash)
        .map_err(GetBondedValidatorsError::StorageErrors)
        .and_then(|maybe_reader| match maybe_reader {
            Some(reader) => match reader.read(correlation_id, &pos_key.normalize()) {
                Ok(Some(Value::Contract(contract))) => {
                    let bonded_validators = contract
                        .urefs_lookup()
                        .keys()
                        .filter_map(|entry| utils::pos_validator_to_tuple(entry))
                        .collect::<HashMap<PublicKey, U512>>();
                    Ok(bonded_validators)
                }
                Ok(_) => Err(GetBondedValidatorsError::PoSNotFound(*pos_key)),
                Err(error) => Err(GetBondedValidatorsError::StorageErrors(error)),
            },
            None => Err(GetBondedValidatorsError::PostStateHashNotFound(root_hash)),
        })
}
