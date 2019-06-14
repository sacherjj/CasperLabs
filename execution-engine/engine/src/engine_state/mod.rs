pub mod error;
pub mod execution_effect;
pub mod execution_result;
pub mod op;
mod utils;

use std::cell::RefCell;
use std::collections::btree_map::BTreeMap;
use std::collections::HashMap;
use std::rc::Rc;
use std::sync::Arc;

use parking_lot::Mutex;
use rand::RngCore;

use common::key::Key;
use common::uref::{AccessRights, URef};
use common::value::account::PurseId;
use common::value::{Contract, Value, U512};
use shared::init;
use shared::newtypes::{Blake2bHash, CorrelationId};
use shared::transform::Transform;
use storage::global_state::{CommitResult, History};
use wasm_prep::wasm_costs::WasmCosts;
use wasm_prep::Preprocessor;

use self::error::{Error, RootNotFound};
use self::execution_result::ExecutionResult;
use engine_state::utils::WasmiBytes;
use execution::{self, Executor};
use tracking_copy::TrackingCopy;

pub struct EngineState<H> {
    // Tracks the "state" of the blockchain (or is an interface to it).
    // I think it should be constrained with a lifetime parameter.
    state: Arc<Mutex<H>>,
    nonce_check: bool,
}

pub fn create_genesis_effects(
    genesis_account_addr: [u8; 32],
    initial_tokens: U512,
    mint_code_bytes: WasmiBytes,
    _proof_of_stake_code_bytes: WasmiBytes,
    protocol_version: u64,
) -> Result<HashMap<Key, Transform>, execution::Error> {
    let mut ret: HashMap<Key, Value> = HashMap::new();
    let mut rng = execution::create_rng(genesis_account_addr, 0, 0);

    // Create (public_uref, mint_contract_uref)

    let public_uref = {
        let mut addr = [0u8; 32];
        rng.fill_bytes(&mut addr);
        URef::new(addr, AccessRights::READ_ADD_WRITE)
    };

    let mint_contract_uref = {
        let mut addr = [0u8; 32];
        rng.fill_bytes(&mut addr);
        URef::new(addr, AccessRights::READ)
    };

    // Store (public_uref, mint_contract_uref) in global state

    ret.insert(
        Key::URef(public_uref),
        Value::Key(Key::URef(mint_contract_uref)),
    );

    let purse_id_uref = {
        let mut addr = [0u8; 32];
        rng.fill_bytes(&mut addr);
        URef::new(addr, AccessRights::READ_ADD_WRITE)
    };

    // Create genesis genesis_account

    let purse_id = PurseId::new(purse_id_uref);

    let genesis_account = init::create_genesis_account(genesis_account_addr, purse_id)?;

    // Store (genesis_account_addr, genesis_account) in global state

    ret.insert(
        Key::Account(genesis_account_addr),
        Value::Account(genesis_account),
    );

    // Initializing and persisting mint
    {
        // Create (purse_id_local_key, balance_uref) (for mint-local state)

        let purse_id_local_key = {
            let seed = mint_contract_uref.addr();
            let local_key = purse_id_uref.addr();
            let key_hash = Blake2bHash::new(&local_key).into();
            Key::Local { seed, key_hash }
        };

        let balance_uref = {
            let mut addr = [0u8; 32];
            rng.fill_bytes(&mut addr);
            URef::new(addr, AccessRights::READ_ADD_WRITE)
        };

        let balance_uref_key = Key::URef(balance_uref);

        // Store (purse_id_local_key, balance_uref_key) in local state

        ret.insert(purse_id_local_key, Value::Key(balance_uref_key));

        // Create balance

        let balance: Value = Value::UInt512(initial_tokens);

        // Store (balance_uref_key, balance) in local state

        ret.insert(balance_uref_key, balance);

        // Create mint_contract

        let mint_known_urefs = {
            let mut ret: BTreeMap<String, Key> = BTreeMap::new();
            ret.insert(public_uref.as_string(), Key::URef(public_uref));
            ret.insert(balance_uref.as_string(), Key::URef(balance_uref));
            ret
        };

        let mint_contract: Contract =
            Contract::new(mint_code_bytes.into(), mint_known_urefs, protocol_version);

        // Store (mint_contract_uref, mint_contract) in global state

        ret.insert(
            Key::URef(mint_contract_uref),
            Value::Contract(mint_contract),
        );
    }

    Ok(ret
        .into_iter()
        .map(|(k, v)| (k, Transform::Write(v)))
        .collect())
}

impl<H> EngineState<H>
where
    H: History,
    H::Error: Into<execution::Error>,
{
    pub fn new(state: H, nonce_check: bool) -> EngineState<H> {
        let state = Arc::new(Mutex::new(state));
        EngineState { state, nonce_check }
    }

    #[allow(clippy::too_many_arguments)]
    pub fn commit_genesis(
        &self,
        correlation_id: CorrelationId,
        genesis_account_addr: [u8; 32],
        initial_tokens: U512,
        mint_code_bytes: &[u8],
        proof_of_stake_code_bytes: &[u8],
        protocol_version: u64,
    ) -> Result<CommitResult, Error> {
        let mint_code_bytes = WasmiBytes::new(mint_code_bytes, WasmCosts::free())?;
        let proof_of_stake_code_bytes =
            WasmiBytes::new(proof_of_stake_code_bytes, WasmCosts::free())?;
        let effects = create_genesis_effects(
            genesis_account_addr,
            initial_tokens,
            mint_code_bytes,
            proof_of_stake_code_bytes,
            protocol_version,
        )?;
        let mut state_guard = self.state.lock();
        let prestate_hash = state_guard.current_root();
        let result = state_guard
            .commit(correlation_id, prestate_hash, effects)
            .map_err(Into::into)?;
        Ok(result)
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
            self.nonce_check,
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

#[cfg(test)]
mod tests {
    use common::value::U512;
    use engine_state::create_genesis_effects;
    use engine_state::utils::WasmiBytes;
    use shared::test_utils;
    use shared::transform::Transform;
    use wasm_prep::wasm_costs::WasmCosts;

    #[test]
    fn create_genesis_effects_creates_expected_effects() {
        let genesis_account_addr = [6u8; 32];

        let initial_tokens = U512::from_dec_str("1000").expect("should create U512");

        let mint_code_bytes = {
            let raw_bytes = test_utils::create_empty_wasm_module_bytes();
            WasmiBytes::new(raw_bytes.as_slice(), WasmCosts::free())
                .expect("should create wasmi bytes")
        };

        let proof_of_stake_bytes = {
            let raw_bytes = test_utils::create_empty_wasm_module_bytes();
            WasmiBytes::new(raw_bytes.as_slice(), WasmCosts::free())
                .expect("should create wasmi bytes")
        };

        let protocol_version = 1;

        let effects = create_genesis_effects(
            genesis_account_addr,
            initial_tokens,
            mint_code_bytes,
            proof_of_stake_bytes,
            protocol_version,
        )
        .expect("should create effects");

        assert_eq!(effects.len(), 5);

        assert!(effects
            .iter()
            .all(|(_, effect)| if let Transform::Write(_) = effect {
                true
            } else {
                false
            }));
    }
}
