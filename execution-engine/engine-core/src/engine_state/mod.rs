use std::cell::RefCell;
use std::collections::{BTreeSet, HashMap};
use std::rc::Rc;
use std::sync::Arc;

use parking_lot::Mutex;

use contract_ffi::bytesrepr::ToBytes;
use contract_ffi::contract_api::argsparser::ArgsParser;
use contract_ffi::key::Key;
use contract_ffi::value::account::{BlockTime, PublicKey};
use contract_ffi::value::{Account, Value, U512};
use engine_shared::newtypes::{Blake2bHash, CorrelationId};
use engine_shared::transform::Transform;
use engine_state::utils::WasmiBytes;
use engine_storage::global_state::{CommitResult, History, StateReader};
use engine_wasm_prep::wasm_costs::WasmCosts;
use engine_wasm_prep::Preprocessor;
use execution::{self, Executor, MINT_NAME, POS_NAME};
use tracking_copy::TrackingCopy;
use tracking_copy_ext::TrackingCopyExt;

pub use self::engine_config::EngineConfig;
use self::error::{Error, RootNotFound};
use self::execution_result::ExecutionResult;
use self::genesis::{create_genesis_effects, GenesisResult};
use engine_state::execution_effect::ExecutionEffect;
use engine_state::genesis::{GENESIS_ACCOUNT, POS_PAYMENT_PURSE, POS_REWARDS_PURSE};

pub mod engine_config;
pub mod error;
pub mod execution_effect;
pub mod execution_result;
pub mod genesis;
pub mod op;
pub mod utils;

// TODO?: MAX_PAYMENT_COST && CONV_RATE values are currently arbitrary w/ real values TBD
pub const MAX_PAYMENT_COST: u64 = 10_000_000;
pub const CONV_RATE: u64 = 10;

#[derive(Debug)]
pub struct EngineState<H> {
    config: EngineConfig,
    state: Arc<Mutex<H>>,
}

impl<H> EngineState<H>
where
    H: History,
    H::Error: Into<execution::Error>,
{
    pub fn new(state: H, config: EngineConfig) -> EngineState<H> {
        let state = Arc::new(Mutex::new(state));
        EngineState { config, state }
    }

    pub fn config(&self) -> &EngineConfig {
        &self.config
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
        session_module_bytes: &[u8],
        session_args: &[u8],
        payment_module_bytes: &[u8],
        payment_args: &[u8],
        address: Key,                         // TODO?: rename 'base_key'
        authorized_keys: BTreeSet<PublicKey>, //TODO?: rename authorization_keys
        blocktime: BlockTime,
        nonce: u64,
        prestate_hash: Blake2bHash,
        gas_limit: u64,
        protocol_version: u64,
        correlation_id: CorrelationId,
        executor: &E,
        preprocessor: &P,
    ) -> Result<ExecutionResult, RootNotFound> {
        // spec: https://casperlabs.atlassian.net/wiki/spaces/EN/pages/123404576/Payment+code+execution+specification
        // DEPLOY PRECONDITIONS

        // Create tracking copy (which functions as a deploy context)
        // validation_spec_2: prestate_hash check
        let tracking_copy = match self.tracking_copy(prestate_hash) {
            Err(error) => return Ok(ExecutionResult::precondition_failure(error)),
            Ok(None) => return Err(RootNotFound(prestate_hash)),
            Ok(Some(mut tracking_copy)) => Rc::new(RefCell::new(tracking_copy)),
        };

        // Get addr bytes from `address` (which is actually a Key)
        // validation_spec_3: account validity
        let account_addr = match address.as_account() {
            Some(addr) => addr,
            None => {
                return Ok(ExecutionResult::precondition_failure(
                    error::Error::AuthorizationError,
                ));
            }
        };

        // Get account from tracking copy
        // validation_spec_3: account validity
        let mut account: Account = match tracking_copy
            .borrow_mut()
            .get_account(correlation_id, account_addr)
        {
            Ok(account) => account,
            Err(_) => {
                return Ok(ExecutionResult::precondition_failure(
                    error::Error::AuthorizationError,
                ));
            }
        };

        // Authorize using provided authorization keys
        // validation_spec_3: account validity
        if authorized_keys.is_empty() || !account.can_authorize(&authorized_keys) {
            return Ok(ExecutionResult::precondition_failure(
                ::engine_state::error::Error::AuthorizationError,
            ));
        }

        // Handle nonce check & increment (NOTE: nonce is scheduled to be removed)
        // validation_spec_3: account validity
        if let Err(error) = tracking_copy.borrow_mut().handle_nonce(&mut account, nonce) {
            return Ok(ExecutionResult::precondition_failure(error.into()));
        }

        // Check total key weight against deploy threshold
        // validation_spec_4: deploy validity
        if !account.can_deploy_with(&authorized_keys) {
            return Ok(ExecutionResult::precondition_failure(
                // TODO?:this doesn't happen in execution any longer, should error variant be moved
                execution::Error::DeploymentAuthorizationFailure.into(),
            ));
        }

        // Create session code `A` from provided session bytes
        // validation_spec_1: valid wasm bytes
        let session_module = match preprocessor.preprocess(session_module_bytes) {
            Err(error) => return Ok(ExecutionResult::precondition_failure(error.into())),
            Ok(module) => module,
        };

        // If payment logic is turned off, execute only session code
        if !(self.config.use_payment_code()) {
            // DEPLOY WITH NO PAYMENT

            // Session code execution
            let session_result = executor.exec(
                session_module,
                session_args,
                address,
                &account,
                authorized_keys,
                blocktime,
                gas_limit,
                protocol_version,
                correlation_id,
                tracking_copy,
            );

            Ok(session_result)
        } else {
            // PAYMENT PRECONDITIONS

            // Get mint system contract URef from account (an account on a different
            //  network may have a mint contract other than the CLMint)
            // payment_code_spec_6: system contract validity
            let mint_public_uref: Key = match account.urefs_lookup().get(MINT_NAME) {
                Some(uref) => uref.normalize(),
                None => {
                    return Ok(ExecutionResult::precondition_failure(
                        Error::MissingSystemContractError(MINT_NAME.to_string()),
                    ));
                }
            };

            // Get mint system contract details
            // payment_code_spec_6: system contract validity
            let mint_info = match tracking_copy
                .borrow_mut()
                .get_system_contract_info(correlation_id, mint_public_uref)
            {
                Ok(contract_info) => contract_info,
                Err(error) => {
                    return Ok(ExecutionResult::precondition_failure(error.into()));
                }
            };

            // Get proof of stake system contract URef from account (an account on a different
            //  network may have a pos contract other than the CLPoS)
            // payment_code_spec_6: system contract validity
            let proof_of_stake_public_uref: Key = match account.urefs_lookup().get(POS_NAME) {
                Some(uref) => uref.normalize(),
                None => {
                    return Ok(ExecutionResult::precondition_failure(
                        Error::MissingSystemContractError(POS_NAME.to_string()),
                    ));
                }
            };

            // Get proof of stake system contract details
            // payment_code_spec_6: system contract validity
            let proof_of_stake_info = match tracking_copy
                .borrow_mut()
                .get_system_contract_info(correlation_id, proof_of_stake_public_uref)
            {
                Ok(contract_info) => contract_info,
                Err(error) => {
                    return Ok(ExecutionResult::precondition_failure(error.into()));
                }
            };

            // Get payment purse Key from proof of stake contract
            // payment_code_spec_6: system contract validity
            let payment_purse_uref_key: Key = match proof_of_stake_info
                .contract
                .urefs_lookup()
                .get(POS_PAYMENT_PURSE)
            {
                Some(key) => key.to_owned(),
                None => return Ok(ExecutionResult::precondition_failure(Error::DeployError)),
            };

            // Get reward purse Key from proof of stake contract
            // payment_code_spec_6: system contract validity
            let rewards_purse_uref_key: Key = match proof_of_stake_info
                .contract
                .urefs_lookup()
                .get(POS_REWARDS_PURSE)
            {
                Some(key) => key.to_owned(),
                None => {
                    return Ok(ExecutionResult::precondition_failure(Error::DeployError));
                }
            };

            // Get account main purse balance mapping key
            // payment_code_spec_6: system contract validity
            let rewards_purse_balance_mapping_key =
                match tracking_copy.borrow_mut().query_purse_balance_key(
                    correlation_id,
                    mint_info.inner_uref,
                    rewards_purse_uref_key,
                ) {
                    Ok(key) => key,
                    Err(error) => {
                        return Ok(ExecutionResult::precondition_failure(error.into()));
                    }
                };

            // Get account main purse balance mapping key
            // validation_spec_5: account main purse minimum balance
            let account_main_purse_balance_mapping_key =
                match tracking_copy.borrow_mut().query_purse_balance_key(
                    correlation_id,
                    mint_info.inner_uref,
                    Key::URef(account.purse_id().value()),
                ) {
                    Ok(key) => key,
                    Err(error) => {
                        return Ok(ExecutionResult::precondition_failure(error.into()));
                    }
                };

            // Get account main purse balance to enforce precondition and in case of forced transfer
            // validation_spec_5: account main purse minimum balance
            let account_main_purse_balance: U512 = match tracking_copy
                .borrow_mut()
                .query_balance_inner(correlation_id, account_main_purse_balance_mapping_key)
            {
                Ok(balance) => balance,
                Err(error) => return Ok(ExecutionResult::precondition_failure(error.into())),
            };

            // Enforce minimum main purse balance validation
            // validation_spec_5: account main purse minimum balance
            let max_payment_cost: U512 = MAX_PAYMENT_COST.into();
            if account_main_purse_balance < max_payment_cost {
                return Ok(ExecutionResult::precondition_failure(
                    Error::InsufficientPaymentError,
                ));
            }

            // Finalization is executed by system account (currently genesis account)
            // payment_code_spec_5: system executes finalization
            let genesis_account = {
                // get genesis account
                let genesis_account_key = account
                    .urefs_lookup()
                    .get(GENESIS_ACCOUNT)
                    .expect("genesis account must exist");

                let genesis_account_addr = match genesis_account_key.as_account() {
                    Some(addr) => addr,
                    None => {
                        return Ok(ExecutionResult::precondition_failure(
                            error::Error::ExecError(execution::Error::KeyNotFound(
                                *genesis_account_key,
                            )),
                        ))
                    }
                };

                match tracking_copy
                    .borrow_mut()
                    .get_account(correlation_id, genesis_account_addr)
                {
                    Ok(account) => account,
                    Err(error) => return Ok(ExecutionResult::precondition_failure(error.into())),
                }
            };

            // `[ExecutionResultBuilder]` handles merging of multiple execution results
            let mut execution_result_builder = execution_result::ExecutionResultBuilder::new();

            // Execute provided payment code
            let payment_result = {
                // payment_code_spec_1: init pay environment w/ gas limit == (max_payment_cost / conv_rate)
                let pay_gas_limit = MAX_PAYMENT_COST / CONV_RATE;

                // Create payment code module from bytes
                // validation_spec_1: valid wasm bytes
                let payment_module = match preprocessor.preprocess(payment_module_bytes) {
                    Err(error) => return Ok(ExecutionResult::precondition_failure(error.into())),
                    Ok(module) => module,
                };

                // payment_code_spec_2: execute payment code
                executor.exec(
                    payment_module,
                    payment_args,
                    address,
                    &account,
                    authorized_keys.clone(),
                    blocktime,
                    pay_gas_limit,
                    protocol_version,
                    correlation_id,
                    Rc::clone(&tracking_copy),
                )
            };

            execution_result_builder.set_payment_execution_result(payment_result);

            // payment_code_spec_3: fork based upon payment purse balance and cost of payment code execution
            let payment_purse_balance: U512 = match tracking_copy.borrow_mut().query_balance(
                correlation_id,
                mint_info.inner_uref,
                payment_purse_uref_key,
            ) {
                Ok(balance) => balance,
                Err(error) => {
                    return Ok(ExecutionResult::precondition_failure(error.into()));
                }
            };

            // payment_code_spec_3_b_ii: if (balance of PoS pay purse) < (gas spent during payment code execution) * conv_rate, no session
            let insufficient_balance_to_continue =
                payment_purse_balance < (execution_result_builder.payment_cost * CONV_RATE).into();

            // payment_code_spec_4: insufficient payment
            if insufficient_balance_to_continue || !execution_result_builder.is_payment_valid {
                let new_balance = account_main_purse_balance - max_payment_cost;

                let mut insufficient_payment_ops = HashMap::new();
                let mut insufficient_payment_transforms = HashMap::new();

                insufficient_payment_ops
                    .insert(account_main_purse_balance_mapping_key, op::Op::Write);
                insufficient_payment_transforms.insert(
                    account_main_purse_balance_mapping_key,
                    Transform::Write(Value::UInt512(new_balance)),
                );

                insufficient_payment_ops.insert(rewards_purse_balance_mapping_key, op::Op::Add);
                insufficient_payment_transforms.insert(
                    rewards_purse_balance_mapping_key,
                    Transform::AddUInt512(max_payment_cost),
                );

                let insufficient_payment_effect =
                    ExecutionEffect::new(insufficient_payment_ops, insufficient_payment_transforms);

                let insufficient_payment_result = ExecutionResult::Success {
                    effect: insufficient_payment_effect,
                    cost: MAX_PAYMENT_COST,
                };

                execution_result_builder
                    .set_insufficient_payment_execution_result(insufficient_payment_result);

                return Ok(execution_result_builder.build());
            }

            // payment_code_spec_3_b_i: if (balance of PoS pay purse) >= (gas spent during payment code execution) * conv_rate, yes session
            // session_code_spec_1: gas limit = ((balance of PoS payment purse) / conv_rate) - (gas spent during payment execution)
            let session_gas_limit: u64 = ((payment_purse_balance / CONV_RATE)
                - execution_result_builder.payment_cost)
                .as_u64();

            // session_code_spec_2: execute session code
            let session_result = executor.exec(
                session_module,
                session_args,
                address,
                &account,
                authorized_keys.clone(),
                blocktime,
                session_gas_limit,
                protocol_version,
                correlation_id,
                Rc::clone(&tracking_copy),
            );

            execution_result_builder.set_session_execution_result(session_result);

            // NOTE: session_code_spec_3: (do not include session execution effects in results) is enforced in execution_result_builder.build()

            // payment_code_spec_5: run finalize process
            let finalize_result = {
                // validation_spec_1: valid wasm bytes
                let proof_of_stake_module = match preprocessor
                    .deserialize(&proof_of_stake_info.module_bytes)
                {
                    Err(error) => return Ok(ExecutionResult::precondition_failure(error.into())),
                    Ok(module) => module,
                };

                let mut proof_of_stake_keys = proof_of_stake_info.contract.urefs_lookup().clone();

                //((gas spent during payment code execution) + (gas spent during session code execution)) * conv_rate
                let finalize_cost = U512::from(execution_result_builder.total_cost());

                let proof_of_stake_args = {
                    let args = ("finalize_payment", finalize_cost, account_addr);
                    ArgsParser::parse(&args)
                        .and_then(|args| args.to_bytes())
                        .expect("args should parse")
                };

                executor.exec_direct(
                    proof_of_stake_module,
                    &proof_of_stake_args,
                    &mut proof_of_stake_keys,
                    proof_of_stake_info.inner_uref_key,
                    &genesis_account,
                    authorized_keys.clone(),
                    blocktime,
                    std::u64::MAX, // <-- this execution should be unlimited but approximating
                    protocol_version,
                    correlation_id,
                    Rc::clone(&tracking_copy),
                )
            };

            execution_result_builder.set_finalize_execution_result(finalize_result);

            let ret = execution_result_builder.build();

            // NOTE: payment_code_spec_5_a is enforced in execution_result_builder.build()
            // payment_code_spec_6: return properly combined set of transforms and appropriate error
            Ok(ret)
        }
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
