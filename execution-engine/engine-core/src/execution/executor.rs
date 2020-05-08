use std::{
    cell::RefCell,
    collections::{BTreeMap, BTreeSet},
    rc::Rc,
};

use parity_wasm::elements::Module;
use wasmi::ModuleRef;

use engine_shared::{
    account::Account, gas::Gas, newtypes::CorrelationId, stored_value::StoredValue,
};
use engine_storage::{global_state::StateReader, protocol_data::ProtocolData};
use types::{
    account::PublicKey, bytesrepr::FromBytes, BlockTime, CLTyped, CLValue, ContractMetadata,
    EntryPoint, Key, Phase, ProtocolVersion, RuntimeArgs,
};

use crate::{
    engine_state::{
        execution_result::ExecutionResult, system_contract_cache::SystemContractCache, EngineConfig,
    },
    execution::{address_generator::AddressGenerator, Error},
    runtime::{extract_access_rights_from_keys, instance_and_memory, Runtime},
    runtime_context::{self, RuntimeContext},
    tracking_copy::TrackingCopy,
};

macro_rules! on_fail_charge {
    ($fn:expr) => {
        match $fn {
            Ok(res) => res,
            Err(e) => {
                let exec_err: crate::execution::Error = e.into();
                log::warn!("Execution failed: {:?}", exec_err);
                return ExecutionResult::precondition_failure(exec_err.into());
            }
        }
    };
    ($fn:expr, $cost:expr) => {
        match $fn {
            Ok(res) => res,
            Err(e) => {
                let exec_err: crate::execution::Error = e.into();
                log::warn!("Execution failed: {:?}", exec_err);
                return ExecutionResult::Failure {
                    error: exec_err.into(),
                    effect: Default::default(),
                    cost: $cost,
                };
            }
        }
    };
    ($fn:expr, $cost:expr, $effect:expr) => {
        match $fn {
            Ok(res) => res,
            Err(e) => {
                let exec_err: crate::execution::Error = e.into();
                log::warn!("Execution failed: {:?}", exec_err);
                return ExecutionResult::Failure {
                    error: exec_err.into(),
                    effect: $effect,
                    cost: $cost,
                };
            }
        }
    };
}

pub struct Executor {
    config: EngineConfig,
}

#[allow(clippy::too_many_arguments)]
impl Executor {
    pub fn new(config: EngineConfig) -> Self {
        Executor { config }
    }

    pub fn config(&self) -> EngineConfig {
        self.config
    }

    pub fn exec<R>(
        &self,
        parity_module: Module,
        entry_point_name: &str,
        args: RuntimeArgs,
        base_key: Key,
        account: &Account,
        mut named_keys: BTreeMap<String, Key>,
        authorization_keys: BTreeSet<PublicKey>,
        blocktime: BlockTime,
        deploy_hash: [u8; 32],
        gas_limit: Gas,
        protocol_version: ProtocolVersion,
        correlation_id: CorrelationId,
        tracking_copy: Rc<RefCell<TrackingCopy<R>>>,
        phase: Phase,
        protocol_data: ProtocolData,
        system_contract_cache: SystemContractCache,
        metadata: ContractMetadata,
        entrypoint: EntryPoint,
    ) -> ExecutionResult
    where
        R: StateReader<Key, StoredValue>,
        R::Error: Into<Error>,
    {
        let (instance, memory) =
            on_fail_charge!(instance_and_memory(parity_module.clone(), protocol_version));

        let access_rights = {
            let keys: Vec<Key> = named_keys.values().cloned().collect();
            // keys.extend(protocol_data.system_contracts().into_iter().map(|uref| {
            //     Key::from(runtime_context::attenuate_uref_for_account(account, uref))
            // }));
            extract_access_rights_from_keys(keys)
        };

        let hash_address_generator = {
            let generator = AddressGenerator::new(&deploy_hash, phase);
            Rc::new(RefCell::new(generator))
        };
        let uref_address_generator = {
            let generator = AddressGenerator::new(&deploy_hash, phase);
            Rc::new(RefCell::new(generator))
        };
        let gas_counter: Gas = Gas::default();

        // Snapshot of effects before execution, so in case of error
        // only nonce update can be returned.
        let effects_snapshot = tracking_copy.borrow().effect();

        let context = RuntimeContext::new(
            tracking_copy,
            &mut named_keys,
            access_rights,
            args.clone(),
            authorization_keys,
            &account,
            base_key,
            blocktime,
            deploy_hash,
            gas_limit,
            gas_counter,
            hash_address_generator,
            uref_address_generator,
            protocol_version,
            correlation_id,
            phase,
            protocol_data,
            metadata.clone(),
            entrypoint.clone(),
        );

        let mut runtime = Runtime::new(
            self.config,
            system_contract_cache,
            memory,
            parity_module,
            context,
        );

        let accounts_access_rights = {
            let keys: Vec<Key> = account.named_keys().values().cloned().collect();
            extract_access_rights_from_keys(keys)
        };

        on_fail_charge!(runtime_context::validate_entry_point_access_with(
            &metadata,
            entrypoint.access(),
            |uref| runtime_context::uref_has_access_rights(uref, &accounts_access_rights)
        ));

        if !self.config.use_system_contracts() {
            if runtime.is_mint(base_key) {
                match runtime.call_host_mint(
                    protocol_version,
                    runtime.context().named_keys().to_owned(),
                    &args,
                    Default::default(),
                ) {
                    Ok(_value) => {
                        return ExecutionResult::Success {
                            effect: runtime.context().effect(),
                            cost: runtime.context().gas_counter(),
                        }
                    }
                    Err(error) => {
                        return ExecutionResult::Failure {
                            error: error.into(),
                            effect: effects_snapshot,
                            cost: runtime.context().gas_counter(),
                        }
                    }
                }
            } else if runtime.is_proof_of_stake(base_key) {
                match runtime.call_host_proof_of_stake(
                    protocol_version,
                    runtime.context().named_keys().to_owned(),
                    &args,
                    Default::default(),
                ) {
                    Ok(_value) => {
                        return ExecutionResult::Success {
                            effect: runtime.context().effect(),
                            cost: runtime.context().gas_counter(),
                        }
                    }
                    Err(error) => {
                        return ExecutionResult::Failure {
                            error: error.into(),
                            effect: effects_snapshot,
                            cost: runtime.context().gas_counter(),
                        }
                    }
                }
            }
        }

        on_fail_charge!(
            instance.invoke_export(entry_point_name, &[], &mut runtime),
            runtime.context().gas_counter(),
            effects_snapshot
        );

        ExecutionResult::Success {
            effect: runtime.context().effect(),
            cost: runtime.context().gas_counter(),
        }
    }

    pub fn exec_finalize<R>(
        &self,
        parity_module: Module,
        args: RuntimeArgs,
        named_keys: &mut BTreeMap<String, Key>,
        base_key: Key,
        account: &Account,
        authorization_keys: BTreeSet<PublicKey>,
        blocktime: BlockTime,
        deploy_hash: [u8; 32],
        gas_limit: Gas,
        protocol_version: ProtocolVersion,
        correlation_id: CorrelationId,
        tracking_copy: Rc<RefCell<TrackingCopy<R>>>,
        phase: Phase,
        protocol_data: ProtocolData,
        system_contract_cache: SystemContractCache,
    ) -> ExecutionResult
    where
        R: StateReader<Key, StoredValue>,
        R::Error: Into<Error>,
    {
        if protocol_data.proof_of_stake() != base_key.into_seed() {
            panic!("exec_finalize should only be called with the proof of stake contract");
        }

        let mut named_keys = named_keys.clone();
        let access_rights = {
            let keys: Vec<Key> = named_keys.values().cloned().collect();
            // keys.extend(protocol_data.system_contracts().into_iter().map(|uref| {
            //     Key::from(runtime_context::attenuate_uref_for_account(account, uref))
            // }));
            extract_access_rights_from_keys(keys)
        };

        let hash_address_generator = {
            let generator = AddressGenerator::new(&deploy_hash, phase);
            Rc::new(RefCell::new(generator))
        };
        let uref_address_generator = {
            let generator = AddressGenerator::new(&deploy_hash, phase);
            Rc::new(RefCell::new(generator))
        };
        let gas_counter = Gas::default(); // maybe const?

        // Snapshot of effects before execution, so in case of error only nonce update
        // can be returned.
        let effects_snapshot = tracking_copy.borrow().effect();

        let context = RuntimeContext::new(
            tracking_copy,
            &mut named_keys,
            access_rights,
            args.clone(),
            authorization_keys,
            &account,
            base_key,
            blocktime,
            deploy_hash,
            gas_limit,
            gas_counter,
            hash_address_generator,
            uref_address_generator,
            protocol_version,
            correlation_id,
            phase,
            protocol_data,
            ContractMetadata::default(),
            EntryPoint::default(),
        );

        let (instance, memory) =
            on_fail_charge!(instance_and_memory(parity_module.clone(), protocol_version));

        let mut runtime = Runtime::new(
            self.config,
            system_contract_cache,
            memory,
            parity_module,
            context,
        );

        if !self.config.use_system_contracts() {
            match runtime.call_host_proof_of_stake(
                protocol_version,
                runtime.context().named_keys().to_owned(),
                &args,
                Default::default(),
            ) {
                Ok(_value) => {
                    return ExecutionResult::Success {
                        effect: runtime.context().effect(),
                        cost: runtime.context().gas_counter(),
                    }
                }
                Err(error) => {
                    return ExecutionResult::Failure {
                        error: error.into(),
                        effect: effects_snapshot,
                        cost: runtime.context().gas_counter(),
                    }
                }
            }
        }

        let error = match instance.invoke_export("call", &[], &mut runtime) {
            Err(error) => error,
            Ok(_) => {
                return ExecutionResult::Success {
                    effect: runtime.context().effect(),
                    cost: runtime.context().gas_counter(),
                }
            }
        };

        if let Some(host_error) = error.as_host_error() {
            let downcasted_error = host_error.downcast_ref::<Error>().unwrap();
            match downcasted_error {
                Error::Ret(ref _ret_urefs) => {
                    return ExecutionResult::Success {
                        effect: runtime.context().effect(),
                        cost: runtime.context().gas_counter(),
                    };
                }
                Error::Revert(status) => {
                    return ExecutionResult::Failure {
                        error: Error::Revert(*status).into(),
                        effect: effects_snapshot,
                        cost: runtime.context().gas_counter(),
                    };
                }
                error => {
                    return ExecutionResult::Failure {
                        error: error.clone().into(),
                        effect: effects_snapshot,
                        cost: runtime.context().gas_counter(),
                    }
                }
            }
        }

        ExecutionResult::Failure {
            error: Error::Interpreter(error.into()).into(),
            effect: effects_snapshot,
            cost: runtime.context().gas_counter(),
        }
    }

    pub fn create_runtime<'a, R>(
        &self,
        module: Module,
        args: RuntimeArgs,
        keys: &'a mut BTreeMap<String, Key>,
        base_key: Key,
        account: &'a Account,
        authorization_keys: BTreeSet<PublicKey>,
        blocktime: BlockTime,
        deploy_hash: [u8; 32],
        gas_limit: Gas,
        hash_address_generator: Rc<RefCell<AddressGenerator>>,
        uref_address_generator: Rc<RefCell<AddressGenerator>>,
        protocol_version: ProtocolVersion,
        correlation_id: CorrelationId,
        tracking_copy: Rc<RefCell<TrackingCopy<R>>>,
        phase: Phase,
        protocol_data: ProtocolData,
        system_contract_cache: SystemContractCache,
        metadata: ContractMetadata,
        entrypoint: EntryPoint,
    ) -> Result<(ModuleRef, Runtime<'a, R>), Error>
    where
        R: StateReader<Key, StoredValue>,
        R::Error: Into<Error>,
    {
        let access_rights = {
            let keys: Vec<Key> = keys.values().cloned().collect();
            // keys.extend(protocol_data.system_contracts().into_iter().map(|uref| {
            //     Key::from(runtime_context::attenuate_uref_for_account(account, uref))
            // }));
            extract_access_rights_from_keys(keys)
        };

        let gas_counter = Gas::default();

        let runtime_context = RuntimeContext::new(
            tracking_copy,
            keys,
            access_rights,
            args,
            authorization_keys,
            account,
            base_key,
            blocktime,
            deploy_hash,
            gas_limit,
            gas_counter,
            hash_address_generator,
            uref_address_generator,
            protocol_version,
            correlation_id,
            phase,
            protocol_data,
            metadata,
            entrypoint,
        );

        let (instance, memory) = instance_and_memory(module.clone(), protocol_version)?;

        let runtime = Runtime::new(
            self.config,
            system_contract_cache,
            memory,
            module,
            runtime_context,
        );

        Ok((instance, runtime))
    }

    pub fn exec_system<R, T>(
        &self,
        module: Module,
        args: RuntimeArgs,
        entry_point_name: &str,
        keys: &mut BTreeMap<String, Key>,
        base_key: Key,
        account: &Account,
        authorization_keys: BTreeSet<PublicKey>,
        blocktime: BlockTime,
        deploy_hash: [u8; 32],
        gas_limit: Gas,
        hash_address_generator: Rc<RefCell<AddressGenerator>>,
        uref_address_generator: Rc<RefCell<AddressGenerator>>,
        protocol_version: ProtocolVersion,
        correlation_id: CorrelationId,
        tracking_copy: Rc<RefCell<TrackingCopy<R>>>,
        phase: Phase,
        protocol_data: ProtocolData,
        system_contract_cache: SystemContractCache,
    ) -> Result<T, Error>
    where
        R: StateReader<Key, StoredValue>,
        R::Error: Into<Error>,
        T: FromBytes + CLTyped,
    {
        let (instance, mut runtime) = self.create_runtime(
            module,
            args,
            keys,
            base_key,
            account,
            authorization_keys,
            blocktime,
            deploy_hash,
            gas_limit,
            hash_address_generator,
            uref_address_generator,
            protocol_version,
            correlation_id,
            tracking_copy,
            phase,
            protocol_data,
            system_contract_cache,
            ContractMetadata::default(),
            EntryPoint::default(),
        )?;

        let error: wasmi::Error = match instance.invoke_export(entry_point_name, &[], &mut runtime)
        {
            Err(error) => error,
            Ok(_) => {
                // This duplicates the behavior of sub_call, but is admittedly rather questionable.
                //
                // If `instance.invoke_export` returns `Ok` and the `host_buffer` is `None`, the
                // contract's execution succeeded but did not explicitly call `runtime::ret()`.
                // Treat as though the execution returned the unit type `()` as per Rust functions
                // which don't specify a return value.
                let result = runtime.take_host_buffer().unwrap_or(CLValue::from_t(())?);
                let ret = result.into_t()?;
                return Ok(ret);
            }
        };

        let return_value: CLValue = match error
            .as_host_error()
            .and_then(|host_error| host_error.downcast_ref::<Error>())
        {
            Some(Error::Ret(_)) => runtime
                .take_host_buffer()
                .ok_or(Error::ExpectedReturnValue)?,
            Some(Error::Revert(code)) => return Err(Error::Revert(*code)),
            Some(error) => return Err(error.clone()),
            _ => return Err(Error::Interpreter(error.into())),
        };

        let ret = return_value.into_t()?;
        Ok(ret)
    }
}
