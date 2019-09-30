use std::cell::RefCell;
use std::collections::{BTreeMap, BTreeSet, HashMap, HashSet};
use std::rc::Rc;

use parity_wasm::elements::Module;

use contract_ffi::bytesrepr::{self, FromBytes};
use contract_ffi::execution::Phase;
use contract_ffi::key::Key;
use contract_ffi::uref::AccessRights;
use contract_ffi::value::account::{BlockTime, PublicKey};
use contract_ffi::value::{Account, ProtocolVersion, Value};
use engine_shared::gas::Gas;
use engine_shared::newtypes::CorrelationId;
use engine_storage::global_state::StateReader;

use crate::engine_state::execution_result::ExecutionResult;

use super::Error;
use super::{extract_access_rights_from_keys, instance_and_memory, Runtime};
use crate::execution::address_generator::AddressGenerator;
use crate::execution::FN_STORE_ID_INITIAL;
use crate::runtime_context::RuntimeContext;
use crate::tracking_copy::TrackingCopy;
use crate::Address;

pub trait Executor<A> {
    #[allow(clippy::too_many_arguments)]
    fn exec<R: StateReader<Key, Value>>(
        &self,
        parity_module: A,
        args: &[u8],
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
    ) -> ExecutionResult
    where
        R::Error: Into<Error>;

    #[allow(clippy::too_many_arguments)]
    fn exec_direct<R: StateReader<Key, Value>>(
        &self,
        parity_module: A,
        args: &[u8],
        keys: &mut BTreeMap<String, Key>,
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
    ) -> ExecutionResult
    where
        R::Error: Into<Error>;

    #[allow(clippy::too_many_arguments)]
    fn better_exec<R: StateReader<Key, Value>, T>(
        &self,
        module: A,
        args: &[u8],
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
    ) -> Result<T, Error>
    where
        R::Error: Into<Error>,
        T: FromBytes;
}

pub struct WasmiExecutor;

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

impl Executor<Module> for WasmiExecutor {
    fn exec<R: StateReader<Key, Value>>(
        &self,
        parity_module: Module,
        args: &[u8],
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
    ) -> ExecutionResult
    where
        R::Error: Into<Error>,
    {
        let (instance, memory) =
            on_fail_charge!(instance_and_memory(parity_module.clone(), protocol_version));

        let mut named_keys = account.named_keys().clone();
        let access_rights = extract_access_rights_from_keys(named_keys.values().cloned());
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
            on_fail_charge!(
                bytesrepr::deserialize(args),
                Gas::from_u64(args.len() as u64),
                effects_snapshot
            )
        };

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
        );

        let mut runtime = Runtime::new(memory, parity_module, context);
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

    fn exec_direct<R: StateReader<Key, Value>>(
        &self,
        parity_module: Module,
        args: &[u8],
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
    ) -> ExecutionResult
    where
        R::Error: Into<Error>,
    {
        let mut named_keys = named_keys.clone();
        let access_rights: HashMap<Address, HashSet<AccessRights>> =
            extract_access_rights_from_keys(named_keys.values().cloned());

        let address_generator = {
            let address_generator = AddressGenerator::new(deploy_hash, phase);
            Rc::new(RefCell::new(address_generator))
        };
        let gas_counter = Gas::default(); // maybe const?

        // Snapshot of effects before execution, so in case of error only nonce update
        // can be returned.
        let effects_snapshot = state.borrow().effect();

        let args: Vec<Vec<u8>> = if args.is_empty() {
            Vec::new()
        } else {
            on_fail_charge!(
                bytesrepr::deserialize(args),
                Gas::from_u64(args.len() as u64),
                effects_snapshot
            )
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
        );

        let (instance, memory) =
            on_fail_charge!(instance_and_memory(parity_module.clone(), protocol_version));

        let mut runtime = Runtime::new(memory, parity_module, context);

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

    fn better_exec<R: StateReader<Key, Value>, T>(
        &self,
        module: Module,
        args: &[u8],
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
    ) -> Result<T, Error>
    where
        R::Error: Into<Error>,
        T: FromBytes,
    {
        let named_keys = extract_access_rights_from_keys(keys.values().cloned());

        let args: Vec<Vec<u8>> = if args.is_empty() {
            Vec::new()
        } else {
            bytesrepr::deserialize(args)?
        };

        let gas_counter = Gas::default();

        let runtime_context = RuntimeContext::new(
            state,
            keys,
            named_keys.clone(),
            args,
            authorization_keys.clone(),
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
        );

        let (instance, memory) = instance_and_memory(module.clone(), protocol_version)?;

        let mut runtime = Runtime::new(memory, module, runtime_context);

        let return_error: wasmi::Error = match instance.invoke_export("call", &[], &mut runtime) {
            Err(error) => error,
            Ok(_) => {
                // This duplicates the behavior of sub_call, but is admittedly rather questionable.
                let ret = bytesrepr::deserialize(runtime.result())?;
                return Ok(ret);
            }
        };

        let return_value_bytes: &[u8] = match return_error
            .as_host_error()
            .and_then(|host_error| host_error.downcast_ref::<Error>())
        {
            Some(Error::Ret(_)) => runtime.result(),
            Some(Error::Revert(code)) => return Err(Error::Revert(*code)),
            _ => return Err(Error::Interpreter(return_error)),
        };

        let ret = bytesrepr::deserialize(return_value_bytes)?;
        Ok(ret)
    }
}
