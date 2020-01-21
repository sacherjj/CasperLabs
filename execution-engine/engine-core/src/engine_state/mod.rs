pub mod deploy_item;
pub mod engine_config;
mod error;
pub mod executable_deploy_item;
pub mod execute_request;
pub mod execution_effect;
pub mod execution_result;
pub mod genesis;
pub mod op;
pub mod query;
pub mod system_contract_cache;
pub mod upgrade;
pub mod utils;

use std::{
    cell::RefCell,
    collections::{BTreeMap, BTreeSet, HashMap},
    rc::Rc,
};

use num_traits::Zero;
use parity_wasm::elements::Module;

use contract::args_parser::ArgsParser;
use engine_shared::{
    account::Account,
    additive_map::AdditiveMap,
    gas::Gas,
    motes::Motes,
    newtypes::{Blake2bHash, CorrelationId},
    stored_value::StoredValue,
    transform::Transform,
};
use engine_storage::{
    global_state::{CommitResult, StateProvider, StateReader},
    protocol_data::ProtocolData,
};
use engine_wasm_prep::{wasm_costs::WasmCosts, Preprocessor};
use types::{
    account::{PublicKey, PurseId},
    bytesrepr::ToBytes,
    system_contract_errors::mint,
    AccessRights, BlockTime, Key, Phase, ProtocolVersion, URef, KEY_HASH_LENGTH, U512,
    UREF_ADDR_LENGTH,
};

use self::{
    deploy_item::DeployItem,
    executable_deploy_item::ExecutableDeployItem,
    execution_result::{ExecutionResult, ForcedTransferResult},
    genesis::{GenesisAccount, GenesisConfig, GenesisResult, POS_PAYMENT_PURSE, POS_REWARDS_PURSE},
    system_contract_cache::SystemContractCache,
};
pub use self::{
    engine_config::EngineConfig,
    error::{Error, RootNotFound},
};
use crate::{
    engine_state::{
        error::Error::MissingSystemContractError,
        execute_request::ExecuteRequest,
        query::{QueryRequest, QueryResult},
        upgrade::{UpgradeConfig, UpgradeResult},
    },
    execution::{self, AddressGenerator, Executor, MINT_NAME, POS_NAME},
    tracking_copy::{TrackingCopy, TrackingCopyExt},
    KnownKeys,
};

// TODO?: MAX_PAYMENT && CONV_RATE values are currently arbitrary w/ real values
// TBD gas * CONV_RATE = motes
pub const MAX_PAYMENT: u64 = 10_000_000;
pub const CONV_RATE: u64 = 10;

pub const SYSTEM_ACCOUNT_ADDR: [u8; 32] = [0u8; 32];

const GENESIS_INITIAL_BLOCKTIME: u64 = 0;
const MINT_METHOD_NAME: &str = "mint";

#[derive(Debug)]
pub struct EngineState<S> {
    config: EngineConfig,
    system_contract_cache: SystemContractCache,
    state: S,
}

impl<S> EngineState<S>
where
    S: StateProvider,
    S::Error: Into<execution::Error>,
{
    pub fn new(state: S, config: EngineConfig) -> EngineState<S> {
        let system_contract_cache = Default::default();
        EngineState {
            config,
            system_contract_cache,
            state,
        }
    }

    pub fn config(&self) -> &EngineConfig {
        &self.config
    }

    pub fn wasm_costs(
        &self,
        protocol_version: ProtocolVersion,
    ) -> Result<Option<WasmCosts>, Error> {
        match self.get_protocol_data(protocol_version)? {
            Some(protocol_data) => Ok(Some(*protocol_data.wasm_costs())),
            None => Ok(None),
        }
    }

    pub fn get_protocol_data(
        &self,
        protocol_version: ProtocolVersion,
    ) -> Result<Option<ProtocolData>, Error> {
        match self.state.get_protocol_data(protocol_version) {
            Ok(Some(protocol_data)) => Ok(Some(protocol_data)),
            Err(error) => Err(Error::ExecError(error.into())),
            _ => Ok(None),
        }
    }

    pub fn commit_genesis(
        &self,
        correlation_id: CorrelationId,
        genesis_config: GenesisConfig,
    ) -> Result<GenesisResult, Error> {
        // Preliminaries
        let executor = Executor;
        let blocktime = BlockTime::new(GENESIS_INITIAL_BLOCKTIME);
        let gas_limit = Gas::new(std::u64::MAX.into());
        let phase = Phase::System;

        let initial_base_key = Key::Account(SYSTEM_ACCOUNT_ADDR);
        let initial_root_hash = self.state.empty_root();
        let protocol_version = genesis_config.protocol_version();
        let wasm_costs = genesis_config.wasm_costs();
        let preprocessor = Preprocessor::new(wasm_costs);

        // Spec #3: Create "virtual system account" object.
        let virtual_system_account = {
            let named_keys = BTreeMap::new();
            let purse = PurseId::new(URef::new(Default::default(), AccessRights::READ_ADD_WRITE));
            Account::create(SYSTEM_ACCOUNT_ADDR, named_keys, purse)
        };

        // Spec #4: Create a runtime.
        let tracking_copy = match self.tracking_copy(initial_root_hash) {
            Ok(Some(tracking_copy)) => Rc::new(RefCell::new(tracking_copy)),
            Ok(None) => panic!("state has not been initialized properly"),
            Err(error) => return Err(error),
        };

        // Persist the "virtual system account".  It will get overwritten with the actual system
        // account below.
        let key = Key::Account(SYSTEM_ACCOUNT_ADDR);
        let value = {
            let virtual_system_account = virtual_system_account.clone();
            StoredValue::Account(virtual_system_account)
        };

        tracking_copy.borrow_mut().write(key, value);

        // Spec #4A: random number generator is seeded from the hash of GenesisConfig.name
        // concatenated with GenesisConfig.timestamp (aka "deploy hash").
        //
        // @birchmd adds: "Probably we will want to include the hash of the cost table in there
        // as well actually. Otherwise it has no direct effect on the genesis post
        // state hash either."
        let install_deploy_hash = {
            let name: &[u8] = genesis_config.name().as_bytes();
            let timestamp: &[u8] = &genesis_config.timestamp().to_le_bytes();
            let wasm_costs_bytes: &[u8] = &wasm_costs.into_bytes()?;
            let bytes: Vec<u8> = {
                let mut ret = Vec::new();
                ret.extend_from_slice(name);
                ret.extend_from_slice(timestamp);
                ret.extend_from_slice(wasm_costs_bytes);
                ret
            };
            Blake2bHash::new(&bytes)
        };

        let address_generator = {
            let generator = AddressGenerator::new(install_deploy_hash.into(), phase);
            Rc::new(RefCell::new(generator))
        };

        // Spec #5: Execute the wasm code from the mint installer bytes
        let mint_reference: URef = {
            let mint_installer_module = {
                let bytes = genesis_config.mint_installer_bytes();
                preprocessor.preprocess(bytes)?
            };
            let args = Vec::new();
            let mut named_keys = BTreeMap::new();
            let authorization_keys: BTreeSet<PublicKey> = BTreeSet::new();
            let install_deploy_hash = install_deploy_hash.into();
            let address_generator = Rc::clone(&address_generator);
            let tracking_copy = Rc::clone(&tracking_copy);
            let system_contract_cache = SystemContractCache::clone(&self.system_contract_cache);

            executor.better_exec(
                mint_installer_module,
                args,
                &mut named_keys,
                initial_base_key,
                &virtual_system_account,
                authorization_keys,
                blocktime,
                install_deploy_hash,
                gas_limit,
                address_generator,
                protocol_version,
                correlation_id,
                tracking_copy,
                phase,
                ProtocolData::default(),
                system_contract_cache,
            )?
        };

        // Spec #7: Execute pos installer wasm code, passing the initially bonded validators as an
        // argument
        let proof_of_stake_reference: URef = {
            let proof_of_stake_installer_module = {
                let bytes = genesis_config.proof_of_stake_installer_bytes();
                preprocessor.preprocess(bytes)?
            };
            let args = {
                // Spec #6: Compute initially bonded validators as the contents of accounts_path
                // filtered to non-zero staked amounts.
                let bonded_validators: BTreeMap<PublicKey, U512> = genesis_config
                    .get_bonded_validators()
                    .map(|(k, v)| (k, v.value()))
                    .collect();
                let args = (mint_reference, bonded_validators);
                ArgsParser::parse(args)
                    .expect("args should convert to `Vec<CLValue>`")
                    .into_bytes()
                    .expect("args should serialize")
            };
            let mut named_keys = BTreeMap::new();
            let authorization_keys: BTreeSet<PublicKey> = BTreeSet::new();
            let install_deploy_hash = install_deploy_hash.into();
            let address_generator = Rc::clone(&address_generator);
            let tracking_copy = Rc::clone(&tracking_copy);
            let system_contract_cache = SystemContractCache::clone(&self.system_contract_cache);

            // Constructs a partial protocol data with already known urefs to pass the validation
            // step
            let partial_protocol_data = ProtocolData::new(
                Default::default(),
                mint_reference,
                // This is used as unknown key
                URef::new([0; 32], AccessRights::READ),
            );

            executor.better_exec(
                proof_of_stake_installer_module,
                args,
                &mut named_keys,
                initial_base_key,
                &virtual_system_account,
                authorization_keys,
                blocktime,
                install_deploy_hash,
                gas_limit,
                address_generator,
                protocol_version,
                correlation_id,
                tracking_copy,
                phase,
                partial_protocol_data,
                system_contract_cache,
            )?
        };

        // Spec #2: Associate given CostTable with given ProtocolVersion.
        let protocol_data = ProtocolData::new(wasm_costs, mint_reference, proof_of_stake_reference);

        self.state
            .put_protocol_data(protocol_version, &protocol_data)
            .map_err(Into::into)?;

        //
        // NOTE: The following stanzas deviate from the implementation strategy described in the
        // original specification.
        //
        // It has the following benefits over that approach:
        // * It does not make an intermediate commit
        // * The system account never holds funds
        // * Similarly, the system account does not need to be handled differently than a normal
        //   account (with the exception of its known keys)
        //

        // Create known keys for chainspec accounts
        let account_named_keys = {
            // After merging in EE-704 system contracts lookup internally uses protocol data and
            // this is used for backwards compatibility with explorer to query mint/pos urefs.
            let mut ret = BTreeMap::new();
            let m_attenuated = URef::new(mint_reference.addr(), AccessRights::READ);
            let p_attenuated = URef::new(proof_of_stake_reference.addr(), AccessRights::READ);
            ret.insert(MINT_NAME.to_string(), Key::URef(m_attenuated));
            ret.insert(POS_NAME.to_string(), Key::URef(p_attenuated));
            ret
        };

        // Create known keys for system account
        let system_account_named_keys = {
            let mut ret = BTreeMap::new();
            ret.insert(MINT_NAME.to_string(), Key::URef(mint_reference));
            ret.insert(POS_NAME.to_string(), Key::URef(proof_of_stake_reference));
            ret
        };

        // Create accounts
        {
            // Collect chainspec accounts and their known keys with the genesis account and its
            // known keys
            let accounts = {
                let mut ret: Vec<(GenesisAccount, KnownKeys)> = genesis_config
                    .accounts()
                    .to_vec()
                    .into_iter()
                    .map(|account| (account, account_named_keys.clone()))
                    .collect();
                let system_account = GenesisAccount::new(
                    PublicKey::new(SYSTEM_ACCOUNT_ADDR),
                    Motes::zero(),
                    Motes::zero(),
                );
                ret.push((system_account, system_account_named_keys));
                ret
            };

            // Get the mint module
            let module = {
                let contract = tracking_copy
                    .borrow_mut()
                    .get_contract(correlation_id, Key::URef(mint_reference))?;
                let (bytes, _, _) = contract.destructure();
                engine_wasm_prep::deserialize(&bytes)?
            };

            // For each account...
            for (account, named_keys) in accounts.into_iter() {
                let module = module.clone();
                let args = {
                    let motes = account.balance().value();
                    let args = (MINT_METHOD_NAME, motes);
                    ArgsParser::parse(args)
                        .expect("args should convert to `Vec<CLValue>`")
                        .into_bytes()
                        .expect("args should serialize")
                };
                let tracking_copy_exec = Rc::clone(&tracking_copy);
                let tracking_copy_write = Rc::clone(&tracking_copy);
                let mut named_keys_exec = BTreeMap::new();
                let base_key = Key::URef(mint_reference);
                let authorization_keys: BTreeSet<PublicKey> = BTreeSet::new();
                let account_public_key = account.public_key();
                let purse_creation_deploy_hash = account_public_key.value();
                let address_generator = {
                    let generator = AddressGenerator::new(purse_creation_deploy_hash, phase);
                    Rc::new(RefCell::new(generator))
                };
                let system_contract_cache = SystemContractCache::clone(&self.system_contract_cache);

                // ...call the Mint's "mint" endpoint to create purse with tokens...
                let mint_result: Result<URef, mint::Error> = executor.better_exec(
                    module,
                    args,
                    &mut named_keys_exec,
                    base_key,
                    &virtual_system_account,
                    authorization_keys,
                    blocktime,
                    purse_creation_deploy_hash,
                    gas_limit,
                    address_generator,
                    protocol_version,
                    correlation_id,
                    tracking_copy_exec,
                    phase,
                    protocol_data,
                    system_contract_cache,
                )?;

                // ...and write that account to global state...
                let key = Key::Account(account_public_key.value());
                let value = {
                    let account_main_purse = mint_result?;
                    let purse_id = PurseId::new(account_main_purse);
                    StoredValue::Account(Account::create(
                        account_public_key.value(),
                        named_keys,
                        purse_id,
                    ))
                };

                tracking_copy_write.borrow_mut().write(key, value);
            }
        }

        // Spec #15: Commit the transforms.
        let effects = tracking_copy.borrow().effect();

        let commit_result = self
            .state
            .commit(
                correlation_id,
                initial_root_hash,
                effects.transforms.to_owned(),
            )
            .map_err(Into::into)?;

        // Return the result
        let genesis_result = GenesisResult::from_commit_result(commit_result, effects);

        Ok(genesis_result)
    }

    pub fn commit_upgrade(
        &self,
        correlation_id: CorrelationId,
        upgrade_config: UpgradeConfig,
    ) -> Result<UpgradeResult, Error> {
        // per specification:
        // https://casperlabs.atlassian.net/wiki/spaces/EN/pages/139854367/Upgrading+System+Contracts+Specification

        // 3.1.1.1.1.1 validate pre state hash exists
        // 3.1.2.1 get a tracking_copy at the provided pre_state_hash
        let pre_state_hash = upgrade_config.pre_state_hash();
        let tracking_copy = match self.tracking_copy(pre_state_hash)? {
            Some(tracking_copy) => Rc::new(RefCell::new(tracking_copy)),
            None => return Ok(UpgradeResult::RootNotFound),
        };

        // 3.1.1.1.1.2 current protocol version is required
        let current_protocol_version = upgrade_config.current_protocol_version();
        let current_protocol_data = match self.state.get_protocol_data(current_protocol_version) {
            Ok(Some(protocol_data)) => protocol_data,
            Ok(None) => {
                return Err(Error::InvalidProtocolVersion(current_protocol_version));
            }
            Err(error) => {
                return Err(Error::ExecError(error.into()));
            }
        };

        // 3.1.1.1.1.3 activation point is not currently used by EE; skipping
        // 3.1.1.1.1.4 upgrade point protocol version validation
        let new_protocol_version = upgrade_config.new_protocol_version();

        let upgrade_check_result =
            current_protocol_version.check_next_version(&new_protocol_version);

        if upgrade_check_result.is_invalid() {
            return Err(Error::InvalidProtocolVersion(new_protocol_version));
        }

        // 3.1.1.1.1.6 resolve wasm CostTable for new protocol version
        let new_wasm_costs = match upgrade_config.wasm_costs() {
            Some(new_wasm_costs) => new_wasm_costs,
            None => *current_protocol_data.wasm_costs(),
        };

        // 3.1.2.2 persist wasm CostTable
        let new_protocol_data = ProtocolData::new(
            new_wasm_costs,
            current_protocol_data.mint(),
            current_protocol_data.proof_of_stake(),
        );

        self.state
            .put_protocol_data(new_protocol_version, &new_protocol_data)
            .map_err(Into::into)?;

        // 3.1.1.1.1.5 upgrade installer is optional except on major version upgrades
        match upgrade_config.upgrade_installer_bytes() {
            None if upgrade_check_result.is_code_required() => {
                // 3.1.1.1.1.5 code is required for major version bump
                return Err(Error::InvalidUpgradeConfig);
            }
            None => {
                // optional for patch/minor bumps
            }
            Some(bytes) => {
                // 3.1.2.3 execute upgrade installer if one is provided

                // preprocess installer module
                let upgrade_installer_module = {
                    let preprocessor = Preprocessor::new(new_wasm_costs);
                    preprocessor.preprocess(bytes)?
                };

                // currently there are no expected args for an upgrade installer but args are
                // supported
                let args = match upgrade_config.upgrade_installer_args() {
                    Some(args) => args.to_vec(),
                    None => vec![],
                };

                // execute as system account
                let system_account = {
                    let key = Key::Account(SYSTEM_ACCOUNT_ADDR);
                    match tracking_copy.borrow_mut().read(correlation_id, &key) {
                        Ok(Some(StoredValue::Account(account))) => account,
                        Ok(_) => panic!("system account must exist"),
                        Err(error) => return Err(Error::ExecError(error.into())),
                    }
                };

                let mut keys = BTreeMap::new();

                let initial_base_key = Key::Account(SYSTEM_ACCOUNT_ADDR);
                let authorization_keys = {
                    let mut ret = BTreeSet::new();
                    ret.insert(PublicKey::new(SYSTEM_ACCOUNT_ADDR));
                    ret
                };

                let blocktime = BlockTime::default();

                let deploy_hash = {
                    // seeds address generator w/ protocol version
                    let bytes: Vec<u8> = upgrade_config
                        .new_protocol_version()
                        .value()
                        .into_bytes()?
                        .to_vec();
                    Blake2bHash::new(&bytes).into()
                };

                // upgrade has no gas limit; approximating with MAX
                let gas_limit = Gas::new(std::u64::MAX.into());
                let phase = Phase::System;
                let address_generator = {
                    let generator = AddressGenerator::new(pre_state_hash.into(), phase);
                    Rc::new(RefCell::new(generator))
                };
                let state = Rc::clone(&tracking_copy);
                let system_contract_cache = SystemContractCache::clone(&self.system_contract_cache);

                Executor.better_exec(
                    upgrade_installer_module,
                    args,
                    &mut keys,
                    initial_base_key,
                    &system_account,
                    authorization_keys,
                    blocktime,
                    deploy_hash,
                    gas_limit,
                    address_generator,
                    new_protocol_version,
                    correlation_id,
                    state,
                    phase,
                    new_protocol_data,
                    system_contract_cache,
                )?
            }
        }

        let effects = tracking_copy.borrow().effect();

        // commit
        let commit_result = self
            .state
            .commit(
                correlation_id,
                pre_state_hash,
                effects.transforms.to_owned(),
            )
            .map_err(Into::into)?;

        // return result and effects
        Ok(UpgradeResult::from_commit_result(commit_result, effects))
    }

    pub fn tracking_copy(
        &self,
        hash: Blake2bHash,
    ) -> Result<Option<TrackingCopy<S::Reader>>, Error> {
        match self.state.checkout(hash).map_err(Into::into)? {
            Some(tc) => Ok(Some(TrackingCopy::new(tc))),
            None => Ok(None),
        }
    }

    pub fn run_query(
        &self,
        correlation_id: CorrelationId,
        query_request: QueryRequest,
    ) -> Result<QueryResult, Error> {
        let tracking_copy = match self.tracking_copy(query_request.state_hash())? {
            Some(tracking_copy) => Rc::new(RefCell::new(tracking_copy)),
            None => return Ok(QueryResult::RootNotFound),
        };

        let mut mut_tracking_copy = tracking_copy.borrow_mut();

        Ok(mut_tracking_copy
            .query(correlation_id, query_request.key(), query_request.path())
            .map_err(|err| Error::ExecError(err.into()))?
            .into())
    }

    pub fn run_execute(
        &self,
        correlation_id: CorrelationId,
        mut exec_request: ExecuteRequest,
    ) -> Result<Vec<ExecutionResult>, RootNotFound> {
        // TODO: do not unwrap
        let wasm_costs = self
            .wasm_costs(exec_request.protocol_version)
            .unwrap()
            .unwrap();
        let executor = Executor;
        let preprocessor = Preprocessor::new(wasm_costs);

        let mut results = Vec::new();

        for deploy_item in exec_request.take_deploys() {
            let result = match deploy_item {
                Ok(deploy_item) => self.deploy(
                    correlation_id,
                    &executor,
                    &preprocessor,
                    exec_request.protocol_version,
                    exec_request.parent_state_hash,
                    BlockTime::new(exec_request.block_time),
                    deploy_item,
                ),
                Err(exec_result) => Ok(exec_result), /* this will get pushed into the results vec
                                                      * below */
            };
            match result {
                Ok(result) => results.push(result),
                Err(error) => {
                    return Err(error);
                }
            };
        }

        Ok(results)
    }

    pub fn get_module(
        &self,
        tracking_copy: Rc<RefCell<TrackingCopy<<S as StateProvider>::Reader>>>,
        deploy_item: &ExecutableDeployItem,
        account: &Account,
        correlation_id: CorrelationId,
        preprocessor: &Preprocessor,
        protocol_version: &ProtocolVersion,
    ) -> Result<Module, error::Error> {
        let stored_contract_key = match deploy_item {
            ExecutableDeployItem::ModuleBytes { module_bytes, .. } => {
                let module = preprocessor.preprocess(&module_bytes)?;
                return Ok(module);
            }
            ExecutableDeployItem::StoredContractByHash { hash, .. } => {
                let hash_len = hash.len();
                if hash_len != KEY_HASH_LENGTH {
                    return Err(error::Error::InvalidHashLength {
                        expected: KEY_HASH_LENGTH,
                        actual: hash_len,
                    });
                }
                let mut arr = [0u8; KEY_HASH_LENGTH];
                arr.copy_from_slice(&hash);
                Key::Hash(arr)
            }
            ExecutableDeployItem::StoredContractByName { name, .. } => {
                let stored_contract_key = account.named_keys().get(name).ok_or_else(|| {
                    error::Error::ExecError(execution::Error::URefNotFound(name.to_string()))
                })?;
                if let Key::URef(uref) = stored_contract_key {
                    if !uref.is_readable() {
                        return Err(error::Error::ExecError(execution::Error::ForgedReference(
                            *uref,
                        )));
                    }
                }
                *stored_contract_key
            }
            ExecutableDeployItem::StoredContractByURef { uref, .. } => {
                let len = uref.len();
                if len != UREF_ADDR_LENGTH {
                    return Err(error::Error::InvalidHashLength {
                        expected: UREF_ADDR_LENGTH,
                        actual: len,
                    });
                }
                let read_only_uref = {
                    let mut arr = [0u8; UREF_ADDR_LENGTH];
                    arr.copy_from_slice(&uref);
                    URef::new(arr, AccessRights::READ)
                };
                let normalized_uref = Key::URef(read_only_uref).normalize();
                let maybe_named_key = account
                    .named_keys()
                    .values()
                    .find(|&named_key| named_key.normalize() == normalized_uref);
                match maybe_named_key {
                    Some(Key::URef(uref)) if uref.is_readable() => normalized_uref,
                    Some(Key::URef(_)) => {
                        return Err(error::Error::ExecError(execution::Error::ForgedReference(
                            read_only_uref,
                        )));
                    }
                    Some(key) => {
                        return Err(error::Error::ExecError(execution::Error::TypeMismatch(
                            engine_shared::transform::TypeMismatch::new(
                                "Key::URef".to_string(),
                                key.type_string(),
                            ),
                        )));
                    }
                    None => {
                        return Err(error::Error::ExecError(execution::Error::KeyNotFound(
                            Key::URef(read_only_uref),
                        )));
                    }
                }
            }
        };
        let contract = tracking_copy
            .borrow_mut()
            .get_contract(correlation_id, stored_contract_key)?;

        // A contract may only call a stored contract that has the same protocol major version
        // number.
        let contract_version = contract.protocol_version();
        if !contract_version.is_compatible_with(&protocol_version) {
            let exec_error = execution::Error::IncompatibleProtocolMajorVersion {
                expected: protocol_version.value().major,
                actual: contract_version.value().major,
            };
            return Err(error::Error::ExecError(exec_error));
        }

        let (ret, _, _) = contract.destructure();
        let module = engine_wasm_prep::deserialize(&ret)?;
        Ok(module)
    }

    #[allow(clippy::too_many_arguments)]
    pub fn deploy(
        &self,
        correlation_id: CorrelationId,
        executor: &Executor,
        preprocessor: &Preprocessor,
        protocol_version: ProtocolVersion,
        prestate_hash: Blake2bHash,
        blocktime: BlockTime,
        deploy_item: DeployItem,
    ) -> Result<ExecutionResult, RootNotFound> {
        // spec: https://casperlabs.atlassian.net/wiki/spaces/EN/pages/123404576/Payment+code+execution+specification

        let session = deploy_item.session;
        let payment = deploy_item.payment;
        let address = Key::Account(deploy_item.address.value());
        let authorization_keys = deploy_item.authorization_keys;
        let deploy_hash = deploy_item.deploy_hash;

        // Create tracking copy (which functions as a deploy context)
        // validation_spec_2: prestate_hash check
        let tracking_copy = match self.tracking_copy(prestate_hash) {
            Err(error) => return Ok(ExecutionResult::precondition_failure(error)),
            Ok(None) => return Err(RootNotFound(prestate_hash)),
            Ok(Some(tracking_copy)) => Rc::new(RefCell::new(tracking_copy)),
        };

        // Get addr bytes from `address` (which is actually a Key)
        // validation_spec_3: account validity
        let account_addr = match address.as_account() {
            Some(account_addr) => account_addr,
            None => {
                return Ok(ExecutionResult::precondition_failure(
                    error::Error::AuthorizationError,
                ))
            }
        };

        // Get account from tracking copy
        // validation_spec_3: account validity
        let account: Account = match tracking_copy
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
        if !account.can_authorize(&authorization_keys) {
            return Ok(ExecutionResult::precondition_failure(
                crate::engine_state::error::Error::AuthorizationError,
            ));
        }

        // Check total key weight against deploy threshold
        // validation_spec_4: deploy validity
        if !account.can_deploy_with(&authorization_keys) {
            return Ok(ExecutionResult::precondition_failure(
                // TODO?:this doesn't happen in execution any longer, should error variant be moved
                execution::Error::DeploymentAuthorizationFailure.into(),
            ));
        }

        // Create session code `A` from provided session bytes
        // validation_spec_1: valid wasm bytes
        let session_module = match self.get_module(
            Rc::clone(&tracking_copy),
            &session,
            &account,
            correlation_id,
            preprocessor,
            &protocol_version,
        ) {
            Ok(module) => module,
            Err(error) => {
                return Ok(ExecutionResult::precondition_failure(error));
            }
        };

        // Obtain current protocol data for given version
        let protocol_data = match self.state.get_protocol_data(protocol_version) {
            Ok(Some(protocol_data)) => protocol_data,
            Ok(None) => {
                let error = Error::InvalidProtocolVersion(protocol_version);
                return Ok(ExecutionResult::precondition_failure(error));
            }
            Err(error) => {
                return Ok(ExecutionResult::precondition_failure(Error::ExecError(
                    error.into(),
                )));
            }
        };

        let max_payment_cost: Motes = Motes::new(U512::from(MAX_PAYMENT));

        // Get mint system contract details
        // payment_code_spec_6: system contract validity
        let mint_reference = {
            // Get mint system contract URef from account (an account on a different network
            // may have a mint contract other than the CLMint)
            // payment_code_spec_6: system contract validity
            let mint_reference = protocol_data.mint();

            let mint_contract = match tracking_copy
                .borrow_mut()
                .get_contract(correlation_id, Key::URef(mint_reference))
            {
                Ok(contract) => contract,
                Err(error) => return Ok(ExecutionResult::precondition_failure(error.into())),
            };

            if !self.system_contract_cache.has(&mint_reference) {
                let module = match engine_wasm_prep::deserialize(mint_contract.bytes()) {
                    Ok(module) => module,
                    Err(error) => return Ok(ExecutionResult::precondition_failure(error.into())),
                };
                self.system_contract_cache.insert(mint_reference, module);
            }

            mint_reference
        };

        // Get proof of stake system contract URef from account (an account on a
        // different network may have a pos contract other than the CLPoS)
        // payment_code_spec_6: system contract validity
        let proof_of_stake_reference = protocol_data.proof_of_stake();

        // Get proof of stake system contract details
        // payment_code_spec_6: system contract validity
        let proof_of_stake_contract = match tracking_copy
            .borrow_mut()
            .get_contract(correlation_id, Key::from(proof_of_stake_reference))
        {
            Ok(contract) => contract,
            Err(error) => {
                return Ok(ExecutionResult::precondition_failure(error.into()));
            }
        };

        // Get rewards purse balance key
        // payment_code_spec_6: system contract validity
        let rewards_purse_balance_key: Key = {
            // Get reward purse Key from proof of stake contract
            // payment_code_spec_6: system contract validity
            let rewards_purse_key: Key =
                match proof_of_stake_contract.named_keys().get(POS_REWARDS_PURSE) {
                    Some(key) => *key,
                    None => {
                        return Ok(ExecutionResult::precondition_failure(Error::DeployError));
                    }
                };

            match tracking_copy.borrow_mut().get_purse_balance_key(
                correlation_id,
                mint_reference,
                rewards_purse_key,
            ) {
                Ok(key) => key,
                Err(error) => {
                    return Ok(ExecutionResult::precondition_failure(error.into()));
                }
            }
        };

        // Get account main purse balance key
        // validation_spec_5: account main purse minimum balance
        let account_main_purse_balance_key: Key = {
            let account_key = Key::URef(account.purse_id().value());
            match tracking_copy.borrow_mut().get_purse_balance_key(
                correlation_id,
                mint_reference,
                account_key,
            ) {
                Ok(key) => key,
                Err(error) => {
                    return Ok(ExecutionResult::precondition_failure(error.into()));
                }
            }
        };

        // Get account main purse balance to enforce precondition and in case of forced
        // transfer validation_spec_5: account main purse minimum balance
        let account_main_purse_balance: Motes = match tracking_copy
            .borrow_mut()
            .get_purse_balance(correlation_id, account_main_purse_balance_key)
        {
            Ok(balance) => balance,
            Err(error) => return Ok(ExecutionResult::precondition_failure(error.into())),
        };

        // Enforce minimum main purse balance validation
        // validation_spec_5: account main purse minimum balance
        if account_main_purse_balance < max_payment_cost {
            return Ok(ExecutionResult::precondition_failure(
                Error::InsufficientPaymentError,
            ));
        }

        // Finalization is executed by system account (currently genesis account)
        // payment_code_spec_5: system executes finalization
        let system_account = Account::new(
            SYSTEM_ACCOUNT_ADDR,
            Default::default(),
            PurseId::new(URef::new(Default::default(), AccessRights::READ_ADD_WRITE)),
            Default::default(),
            Default::default(),
        );

        // `[ExecutionResultBuilder]` handles merging of multiple execution results
        let mut execution_result_builder = execution_result::ExecutionResultBuilder::new();

        // Execute provided payment code
        let payment_result = {
            // payment_code_spec_1: init pay environment w/ gas limit == (max_payment_cost /
            // conv_rate)
            let pay_gas_limit = Gas::from_motes(max_payment_cost, CONV_RATE).unwrap_or_default();

            // Create payment code module from bytes
            // validation_spec_1: valid wasm bytes
            let payment_module = match self.get_module(
                Rc::clone(&tracking_copy),
                &payment,
                &account,
                correlation_id,
                preprocessor,
                &protocol_version,
            ) {
                Ok(module) => module,
                Err(error) => {
                    return Ok(ExecutionResult::precondition_failure(error));
                }
            };
            let system_contract_cache = SystemContractCache::clone(&self.system_contract_cache);

            // payment_code_spec_2: execute payment code
            executor.exec(
                payment_module,
                payment.take_args(),
                address,
                &account,
                authorization_keys.clone(),
                blocktime,
                deploy_hash,
                pay_gas_limit,
                protocol_version,
                correlation_id,
                Rc::clone(&tracking_copy),
                Phase::Payment,
                protocol_data,
                system_contract_cache,
            )
        };

        let payment_result_cost = payment_result.cost();

        // payment_code_spec_3: fork based upon payment purse balance and cost of
        // payment code execution
        let payment_purse_balance: Motes = {
            // Get payment purse Key from proof of stake contract
            // payment_code_spec_6: system contract validity
            let payment_purse: Key =
                match proof_of_stake_contract.named_keys().get(POS_PAYMENT_PURSE) {
                    Some(key) => *key,
                    None => return Ok(ExecutionResult::precondition_failure(Error::DeployError)),
                };

            let purse_balance_key = match tracking_copy.borrow_mut().get_purse_balance_key(
                correlation_id,
                mint_reference,
                payment_purse,
            ) {
                Ok(key) => key,
                Err(error) => {
                    return Ok(ExecutionResult::precondition_failure(error.into()));
                }
            };

            match tracking_copy
                .borrow_mut()
                .get_purse_balance(correlation_id, purse_balance_key)
            {
                Ok(balance) => balance,
                Err(error) => {
                    return Ok(ExecutionResult::precondition_failure(error.into()));
                }
            }
        };

        if let Some(forced_transfer) = payment_result.check_forced_transfer(payment_purse_balance) {
            let error = match forced_transfer {
                ForcedTransferResult::InsufficientPayment => Error::InsufficientPaymentError,
                ForcedTransferResult::PaymentFailure => payment_result.take_error().unwrap(),
            };
            return Ok(ExecutionResult::new_payment_code_error(
                error,
                max_payment_cost,
                account_main_purse_balance,
                account_main_purse_balance_key,
                rewards_purse_balance_key,
            ));
        }

        execution_result_builder.set_payment_execution_result(payment_result);

        let post_payment_tc = tracking_copy.borrow();
        let session_tc = Rc::new(RefCell::new(post_payment_tc.fork()));

        // session_code_spec_2: execute session code
        let session_result = {
            // payment_code_spec_3_b_i: if (balance of PoS pay purse) >= (gas spent during
            // payment code execution) * conv_rate, yes session
            // session_code_spec_1: gas limit = ((balance of PoS payment purse) / conv_rate)
            // - (gas spent during payment execution)
            let session_gas_limit: Gas = Gas::from_motes(payment_purse_balance, CONV_RATE)
                .unwrap_or_default()
                - payment_result_cost;
            let system_contract_cache = SystemContractCache::clone(&self.system_contract_cache);

            executor.exec(
                session_module,
                session.take_args(),
                address,
                &account,
                authorization_keys.clone(),
                blocktime,
                deploy_hash,
                session_gas_limit,
                protocol_version,
                correlation_id,
                Rc::clone(&session_tc),
                Phase::Session,
                protocol_data,
                system_contract_cache,
            )
        };

        let post_session_rc = if session_result.is_failure() {
            // If session code fails we do not include its effects,
            // so we start again from the post-payment state.
            Rc::new(RefCell::new(post_payment_tc.fork()))
        } else {
            session_tc
        };

        // NOTE: session_code_spec_3: (do not include session execution effects in
        // results) is enforced in execution_result_builder.build()
        execution_result_builder.set_session_execution_result(session_result);

        // payment_code_spec_5: run finalize process
        let finalize_result = {
            let post_session_tc = post_session_rc.borrow();
            let finalization_tc = Rc::new(RefCell::new(post_session_tc.fork()));

            // validation_spec_1: valid wasm bytes
            let proof_of_stake_module =
                match self.system_contract_cache.get(&proof_of_stake_reference) {
                    Some(module) => module,
                    None => {
                        let module =
                            match engine_wasm_prep::deserialize(proof_of_stake_contract.bytes()) {
                                Ok(module) => module,
                                Err(error) => {
                                    return Ok(ExecutionResult::precondition_failure(error.into()))
                                }
                            };
                        self.system_contract_cache
                            .insert(proof_of_stake_reference, module.clone());
                        module
                    }
                };

            let proof_of_stake_args = {
                //((gas spent during payment code execution) + (gas spent during session code execution)) * conv_rate
                let finalize_cost_motes: Motes = Motes::from_gas(execution_result_builder.total_cost(), CONV_RATE).expect("motes overflow");
                let args = ("finalize_payment", finalize_cost_motes.value(), account_addr);
                ArgsParser::parse(args)
                    .expect("args should convert to `Vec<CLValue>`")
                    .into_bytes()
                    .expect("args should serialize")
            };

            // The PoS keys may have changed because of effects during payment and/or
            // session, so we need to look them up again from the tracking copy
            let proof_of_stake_contract = match finalization_tc
                .borrow_mut()
                .get_contract(correlation_id, Key::URef(proof_of_stake_reference))
            {
                Ok(info) => info,
                Err(error) => return Ok(ExecutionResult::precondition_failure(error.into())),
            };

            let mut proof_of_stake_keys = proof_of_stake_contract.named_keys().to_owned();

            let base_key = Key::from(proof_of_stake_reference);
            let gas_limit = Gas::new(U512::from(std::u64::MAX));
            let system_contract_cache = SystemContractCache::clone(&self.system_contract_cache);

            executor.exec_direct(
                proof_of_stake_module,
                proof_of_stake_args,
                &mut proof_of_stake_keys,
                base_key,
                &system_account,
                authorization_keys,
                blocktime,
                deploy_hash,
                gas_limit,
                protocol_version,
                correlation_id,
                finalization_tc,
                Phase::FinalizePayment,
                protocol_data,
                system_contract_cache,
            )
        };

        execution_result_builder.set_finalize_execution_result(finalize_result);

        // We panic here to indicate that the builder was not used properly.
        let ret = execution_result_builder
            .build(tracking_copy.borrow().reader(), correlation_id)
            .expect("ExecutionResultBuilder not initialized properly");

        // NOTE: payment_code_spec_5_a is enforced in execution_result_builder.build()
        // payment_code_spec_6: return properly combined set of transforms and
        // appropriate error
        Ok(ret)
    }

    pub fn apply_effect(
        &self,
        correlation_id: CorrelationId,
        protocol_version: ProtocolVersion,
        pre_state_hash: Blake2bHash,
        effects: AdditiveMap<Key, Transform>,
    ) -> Result<CommitResult, Error>
    where
        Error: From<S::Error>,
    {
        match self.state.commit(correlation_id, pre_state_hash, effects)? {
            CommitResult::Success { state_root, .. } => {
                let bonded_validators =
                    self.get_bonded_validators(correlation_id, protocol_version, state_root)?;
                Ok(CommitResult::Success {
                    state_root,
                    bonded_validators,
                })
            }
            commit_result => Ok(commit_result),
        }
    }

    /// Calculates bonded validators at `root_hash` state.
    ///
    /// Should only be called with a valid root hash after a successful call to
    /// [`StateProvider::commit`]. Will panic if called with an invalid root hash.
    fn get_bonded_validators(
        &self,
        correlation_id: CorrelationId,
        protocol_version: ProtocolVersion,
        root_hash: Blake2bHash,
    ) -> Result<HashMap<PublicKey, U512>, Error>
    where
        Error: From<S::Error>,
    {
        let protocol_data = match self.state.get_protocol_data(protocol_version)? {
            Some(protocol_data) => protocol_data,
            None => return Err(Error::InvalidProtocolVersion(protocol_version)),
        };

        let proof_of_stake = {
            let tmp = protocol_data.proof_of_stake();
            Key::URef(tmp).normalize()
        };

        let reader = match self.state.checkout(root_hash)? {
            Some(reader) => reader,
            None => panic!("get_bonded_validators called with an invalid root hash"),
        };

        let contract = match reader.read(correlation_id, &proof_of_stake)? {
            Some(StoredValue::Contract(contract)) => contract,
            _ => return Err(MissingSystemContractError("proof of stake".to_string())),
        };

        let bonded_validators = contract
            .named_keys()
            .keys()
            .filter_map(|entry| utils::pos_validator_key_name_to_tuple(entry))
            .collect::<HashMap<PublicKey, U512>>();

        Ok(bonded_validators)
    }
}
