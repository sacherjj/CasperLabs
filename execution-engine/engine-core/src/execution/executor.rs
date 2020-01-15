use std::{
    cell::RefCell,
    collections::{BTreeMap, BTreeSet},
    rc::Rc,
};

use parity_wasm::elements::Module;

use engine_shared::{
    account::Account, gas::Gas, newtypes::CorrelationId, stored_value::StoredValue,
};
use engine_storage::{global_state::StateReader, protocol_data::ProtocolData};
use types::{
    account::PublicKey,
    bytesrepr::{self, FromBytes},
    BlockTime, CLType, CLTyped, CLValue, Key, Phase, ProtocolVersion,
};

use super::{extract_access_rights_from_keys, instance_and_memory, Error, Runtime};
use crate::{
    engine_state::{execution_result::ExecutionResult, system_contract_cache::SystemContractCache},
    execution::{address_generator::AddressGenerator, FN_STORE_ID_INITIAL},
    runtime_context::{self, RuntimeContext},
    tracking_copy::TrackingCopy,
};

macro_rules! on_fail_charge {
    ($fn:expr) => {
        match $fn {
            Ok(res) => res,
            Err(e) => {
                let exec_err: crate::execution::Error = e.into();
                return ExecutionResult::precondition_failure(exec_err.into());
            }
        }
    };
    ($fn:expr, $cost:expr) => {
        match $fn {
            Ok(res) => res,
            Err(e) => {
                let exec_err: crate::execution::Error = e.into();
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
                return ExecutionResult::Failure {
                    error: exec_err.into(),
                    effect: $effect,
                    cost: $cost,
                };
            }
        }
    };
}

pub struct Executor;

#[allow(clippy::too_many_arguments)]
impl Executor {
    pub fn exec<R>(
        &self,
        parity_module: Module,
        args: Vec<u8>,
        base_key: Key,
        account: &Account,
        authorized_keys: BTreeSet<PublicKey>,
        blocktime: BlockTime,
        deploy_hash: [u8; 32],
        gas_limit: Gas,
        protocol_version: ProtocolVersion,
        correlation_id: CorrelationId,
        tc: Rc<RefCell<TrackingCopy<R>>>,
        phase: Phase,
        protocol_data: ProtocolData,
        system_contract_cache: SystemContractCache,
    ) -> ExecutionResult
    where
        R: StateReader<Key, StoredValue>,
        R::Error: Into<Error>,
    {
        let (instance, memory) =
            on_fail_charge!(instance_and_memory(parity_module.clone(), protocol_version));

        let mut named_keys = account.named_keys().clone();

        let access_rights =
            {
                let mut keys: Vec<Key> = named_keys.values().cloned().collect();
                keys.extend(protocol_data.system_contracts().into_iter().map(|uref| {
                    Key::from(runtime_context::attenuate_uref_for_account(account, uref))
                }));
                extract_access_rights_from_keys(keys)
            };

        let address_generator = AddressGenerator::new(deploy_hash, phase);
        let gas_counter: Gas = Gas::default();

        // Snapshot of effects before execution, so in case of error
        // only nonce update can be returned.
        let effects_snapshot = tc.borrow().effect();

        let arguments: Vec<Vec<u8>> = if args.is_empty() {
            Vec::new()
        } else {
            // TODO: figure out how this works with the cost model
            // https://casperlabs.atlassian.net/browse/EE-239
            let gas = Gas::new(args.len().into());
            on_fail_charge!(bytesrepr::deserialize(args), gas, effects_snapshot)
        };

        let arguments = arguments
            .into_iter()
            .map(|bytes: Vec<u8>| CLValue::from_components(CLType::Any, bytes))
            .collect();

        let context = RuntimeContext::new(
            tc,
            &mut named_keys,
            access_rights,
            arguments,
            authorized_keys,
            &account,
            base_key,
            blocktime,
            deploy_hash,
            gas_limit,
            gas_counter,
            FN_STORE_ID_INITIAL,
            Rc::new(RefCell::new(address_generator)),
            protocol_version,
            correlation_id,
            phase,
            protocol_data,
        );

        let mut runtime = Runtime::new(system_contract_cache, memory, parity_module, context);
        on_fail_charge!(
            instance.invoke_export("call", &[], &mut runtime),
            runtime.context().gas_counter(),
            effects_snapshot
        );

        ExecutionResult::Success {
            effect: runtime.context().effect(),
            cost: runtime.context().gas_counter(),
        }
    }

    pub fn exec_direct<R>(
        &self,
        parity_module: Module,
        args: Vec<u8>,
        named_keys: &mut BTreeMap<String, Key>,
        base_key: Key,
        account: &Account,
        authorization_keys: BTreeSet<PublicKey>,
        blocktime: BlockTime,
        deploy_hash: [u8; 32],
        gas_limit: Gas,
        protocol_version: ProtocolVersion,
        correlation_id: CorrelationId,
        state: Rc<RefCell<TrackingCopy<R>>>,
        phase: Phase,
        protocol_data: ProtocolData,
        system_contract_cache: SystemContractCache,
    ) -> ExecutionResult
    where
        R: StateReader<Key, StoredValue>,
        R::Error: Into<Error>,
    {
        let mut named_keys = named_keys.clone();
        let access_rights =
            {
                let mut keys: Vec<Key> = named_keys.values().cloned().collect();
                keys.extend(protocol_data.system_contracts().into_iter().map(|uref| {
                    Key::from(runtime_context::attenuate_uref_for_account(account, uref))
                }));
                extract_access_rights_from_keys(keys)
            };

        let address_generator = {
            let address_generator = AddressGenerator::new(deploy_hash, phase);
            Rc::new(RefCell::new(address_generator))
        };
        let gas_counter = Gas::default(); // maybe const?

        // Snapshot of effects before execution, so in case of error only nonce update
        // can be returned.
        let effects_snapshot = state.borrow().effect();

        let args: Vec<CLValue> = if args.is_empty() {
            Vec::new()
        } else {
            let gas = Gas::new(args.len().into());
            on_fail_charge!(bytesrepr::deserialize(args), gas, effects_snapshot)
        };

        let context = RuntimeContext::new(
            state,
            &mut named_keys,
            access_rights,
            args,
            authorization_keys,
            &account,
            base_key,
            blocktime,
            deploy_hash,
            gas_limit,
            gas_counter,
            FN_STORE_ID_INITIAL,
            address_generator,
            protocol_version,
            correlation_id,
            phase,
            protocol_data,
        );

        let (instance, memory) =
            on_fail_charge!(instance_and_memory(parity_module.clone(), protocol_version));

        let mut runtime = Runtime::new(system_contract_cache, memory, parity_module, context);

        match instance.invoke_export("call", &[], &mut runtime) {
            Ok(_) => ExecutionResult::Success {
                effect: runtime.context().effect(),
                cost: runtime.context().gas_counter(),
            },
            Err(e) => {
                if let Some(host_error) = e.as_host_error() {
                    // `ret` Trap is a success; downcast and attempt to extract result
                    let downcasted_error = host_error.downcast_ref::<Error>().unwrap();
                    match downcasted_error {
                        Error::Ret(ref _ret_urefs) => {
                            // NOTE: currently, ExecutionResult does not include runtime.result or
                            // extra urefs  and thus we cannot get back
                            // a value from the executed contract...
                            // TODO?: add ability to include extra_urefs and runtime.result to
                            // ExecutionResult::Success

                            return ExecutionResult::Success {
                                effect: runtime.context().effect(),
                                cost: runtime.context().gas_counter(),
                            };
                        }
                        Error::Revert(status) => {
                            // Propagate revert as revert, instead of passing it as
                            // InterpreterError.
                            return ExecutionResult::Failure {
                                error: Error::Revert(*status).into(),
                                effect: effects_snapshot,
                                cost: runtime.context().gas_counter(),
                            };
                        }
                        _ => {}
                    }
                }

                ExecutionResult::Failure {
                    error: Error::Interpreter(e).into(),
                    effect: effects_snapshot,
                    cost: runtime.context().gas_counter(),
                }
            }
        }
    }

    pub fn better_exec<R, T>(
        &self,
        module: Module,
        args: Vec<u8>,
        keys: &mut BTreeMap<String, Key>,
        base_key: Key,
        account: &Account,
        authorization_keys: BTreeSet<PublicKey>,
        blocktime: BlockTime,
        deploy_hash: [u8; 32],
        gas_limit: Gas,
        address_generator: Rc<RefCell<AddressGenerator>>,
        protocol_version: ProtocolVersion,
        correlation_id: CorrelationId,
        state: Rc<RefCell<TrackingCopy<R>>>,
        phase: Phase,
        protocol_data: ProtocolData,
        system_contract_cache: SystemContractCache,
    ) -> Result<T, Error>
    where
        R: StateReader<Key, StoredValue>,
        R::Error: Into<Error>,
        T: FromBytes + CLTyped,
    {
        let access_rights =
            {
                let mut keys: Vec<Key> = keys.values().cloned().collect();
                keys.extend(protocol_data.system_contracts().into_iter().map(|uref| {
                    Key::from(runtime_context::attenuate_uref_for_account(account, uref))
                }));
                extract_access_rights_from_keys(keys)
            };

        let args: Vec<CLValue> = if args.is_empty() {
            Vec::new()
        } else {
            bytesrepr::deserialize(args)?
        };

        let gas_counter = Gas::default();

        let runtime_context = RuntimeContext::new(
            state,
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
            FN_STORE_ID_INITIAL,
            address_generator,
            protocol_version,
            correlation_id,
            phase,
            protocol_data,
        );

        let (instance, memory) = instance_and_memory(module.clone(), protocol_version)?;

        let mut runtime = Runtime::new(system_contract_cache, memory, module, runtime_context);

        let return_error: wasmi::Error = match instance.invoke_export("call", &[], &mut runtime) {
            Err(error) => error,
            Ok(_) => {
                // This duplicates the behavior of sub_call, but is admittedly rather questionable.
                //
                // If `instance.invoke_export` returns `Ok` and the `host_buf` is `None`, the
                // contract's execution succeeded but did not explicitly call `runtime::ret()`.
                // Treat as though the execution returned the unit type `()` as per Rust functions
                // which don't specify a return value.
                let result = runtime.take_host_buf().unwrap_or(CLValue::from_t(())?);
                let ret = result.into_t()?;
                return Ok(ret);
            }
        };

        let return_value: CLValue = match return_error
            .as_host_error()
            .and_then(|host_error| host_error.downcast_ref::<Error>())
        {
            Some(Error::Ret(_)) => runtime.take_host_buf().ok_or(Error::ExpectedReturnValue)?,
            Some(Error::Revert(code)) => return Err(Error::Revert(*code)),
            _ => return Err(Error::Interpreter(return_error)),
        };

        let ret = return_value.into_t()?;
        Ok(ret)
    }
}
