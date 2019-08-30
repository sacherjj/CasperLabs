use std::cell::RefCell;
use std::collections::{BTreeMap, BTreeSet, HashMap, HashSet};
use std::rc::Rc;

use parity_wasm::elements::Module;

use crate::engine_state::execution_result::ExecutionResult;
use contract_ffi::bytesrepr::deserialize;
use contract_ffi::execution::Phase;
use contract_ffi::key::Key;
use contract_ffi::uref::AccessRights;
use contract_ffi::value::account::{BlockTime, PublicKey};
use contract_ffi::value::{Account, Value};
use engine_shared::newtypes::CorrelationId;
use engine_storage::global_state::StateReader;

use super::Error;
use super::{create_rng, extract_access_rights_from_keys, instance_and_memory, Runtime};
use crate::runtime_context::RuntimeContext;
use crate::tracking_copy::TrackingCopy;
use crate::URefAddr;

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
        gas_limit: u64,
        protocol_version: u64,
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
        gas_limit: u64,
        protocol_version: u64,
        correlation_id: CorrelationId,
        state: Rc<RefCell<TrackingCopy<R>>>,
        phase: Phase,
    ) -> ExecutionResult
    where
        R::Error: Into<Error>;
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
        gas_limit: u64,
        protocol_version: u64,
        correlation_id: CorrelationId,
        tc: Rc<RefCell<TrackingCopy<R>>>,
        phase: Phase,
    ) -> ExecutionResult
    where
        R::Error: Into<Error>,
    {
        let (instance, memory) =
            on_fail_charge!(instance_and_memory(parity_module.clone(), protocol_version));

        let mut uref_lookup_local = account.urefs_lookup().clone();
        let known_urefs: HashMap<URefAddr, HashSet<AccessRights>> =
            extract_access_rights_from_keys(uref_lookup_local.values().cloned());
        let account_bytes = base_key.as_account().unwrap();
        let rng = create_rng(account_bytes, account.nonce());
        let gas_counter = 0u64;
        let fn_store_id = 0u32;

        // Snapshot of effects before execution, so in case of error
        // only nonce update can be returned.
        let effects_snapshot = tc.borrow().effect();

        let arguments: Vec<Vec<u8>> = if args.is_empty() {
            Vec::new()
        } else {
            // TODO: figure out how this works with the cost model
            // https://casperlabs.atlassian.net/browse/EE-239
            on_fail_charge!(deserialize(args), args.len() as u64, effects_snapshot)
        };

        let context = RuntimeContext::new(
            tc,
            &mut uref_lookup_local,
            known_urefs,
            arguments,
            authorized_keys,
            &account,
            base_key,
            blocktime,
            gas_limit,
            gas_counter,
            fn_store_id,
            Rc::new(RefCell::new(rng)),
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
        keys: &mut BTreeMap<String, Key>,
        base_key: Key,
        account: &Account,
        authorization_keys: BTreeSet<PublicKey>,
        blocktime: BlockTime,
        gas_limit: u64,
        protocol_version: u64,
        correlation_id: CorrelationId,
        state: Rc<RefCell<TrackingCopy<R>>>,
        phase: Phase,
    ) -> ExecutionResult
    where
        R::Error: Into<Error>,
    {
        let mut uref_lookup = keys.clone();
        let known_urefs: HashMap<URefAddr, HashSet<AccessRights>> =
            extract_access_rights_from_keys(uref_lookup.values().cloned());

        //let base_key = Key::Account(account.pub_key());
        let rng = {
            let rng = create_rng(account.pub_key(), account.nonce());
            Rc::new(RefCell::new(rng))
        };
        let gas_counter = 0u64; // maybe const?
        let fn_store_id = 0u32; // maybe const?

        // Snapshot of effects before execution, so in case of error only nonce update
        // can be returned.
        let effects_snapshot = state.borrow().effect();

        let args: Vec<Vec<u8>> = if args.is_empty() {
            Vec::new()
        } else {
            on_fail_charge!(deserialize(args), args.len() as u64, effects_snapshot)
        };

        let context = RuntimeContext::new(
            state,
            &mut uref_lookup,
            known_urefs,
            args,
            authorization_keys,
            &account,
            base_key,
            blocktime,
            gas_limit,
            gas_counter,
            fn_store_id,
            rng,
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
}
