use crate::support::test_support::{self, WasmTestBuilder};
use contract_ffi::bytesrepr::{self, FromBytes};
use contract_ffi::execution::Phase;
use contract_ffi::key::Key;
use contract_ffi::uref::URef;
use contract_ffi::value::account::BlockTime;
use engine_core::engine_state::executable_deploy_item::ExecutableDeployItem;
use engine_core::engine_state::execution_effect::ExecutionEffect;
use engine_core::engine_state::EngineState;
use engine_core::execution;
use engine_core::execution::AddressGenerator;
use engine_core::runtime_context::RuntimeContext;
use engine_grpc_server::engine_server::ipc_grpc::ExecutionEngineService;
use engine_shared::gas::Gas;
use engine_shared::newtypes::CorrelationId;
use engine_storage::global_state::StateProvider;
use engine_wasm_prep::wasm_costs::WasmCosts;
use engine_wasm_prep::WasmiPreprocessor;
use std::cell::RefCell;
use std::collections::BTreeSet;
use std::convert::TryInto;
use std::rc::Rc;

const INIT_FN_STORE_ID: u32 = 0;
const INIT_PROTOCOL_VERSION: u64 = 1;

/// This function allows executing the contract stored in the given `wasm_file`, while capturing the
/// output. It is essentially the same functionality as `Executor::exec`, but the return value of
/// the contract is returned along with the effects. The purpose of this function is to test
/// installer contracts used in the new genesis process.
pub fn exec<S, T>(
    builder: &mut WasmTestBuilder<S>,
    address: [u8; 32],
    wasm_file: &str,
    block_time: u64,
    deploy_hash: [u8; 32],
    args: impl contract_ffi::contract_api::argsparser::ArgsParser,
    extra_urefs: Vec<URef>,
) -> Option<(T, Vec<URef>, ExecutionEffect)>
where
    S: StateProvider,
    S::Error: Into<execution::Error>,
    EngineState<S>: ExecutionEngineService,
    T: FromBytes,
{
    let prestate = builder
        .get_post_state_hash()
        .as_slice()
        .try_into()
        .expect("should be able to make Blake2bHash from post-state hash");
    let tracking_copy = Rc::new(RefCell::new(
        builder
            .get_engine_state()
            .tracking_copy(prestate)
            .unwrap()
            .expect("should be able to checkout tracking copy"),
    ));

    let phase = Phase::Session;
    let address_generator = {
        let address_generator = AddressGenerator::new(deploy_hash, phase);
        Rc::new(RefCell::new(address_generator))
    };
    let gas_counter = Gas::default();
    let fn_store_id = INIT_FN_STORE_ID;
    let gas_limit = Gas::from_u64(std::u64::MAX);
    let protocol_version = INIT_PROTOCOL_VERSION;
    let correlation_id = CorrelationId::new();
    let arguments: Vec<Vec<u8>> = args.parse().expect("should be able to serialize args");

    let base_key = Key::Account(address);
    let account = builder.get_account(base_key).expect("should find account");

    let mut uref_lookup = account.urefs_lookup().clone();
    let known_urefs = {
        let mut from_account =
            execution::extract_access_rights_from_keys(uref_lookup.values().cloned());
        let from_extra = execution::extract_access_rights_from_urefs(extra_urefs.into_iter());

        from_account.extend(from_extra.into_iter());

        from_account
    };

    let context = RuntimeContext::new(
        Rc::clone(&tracking_copy),
        &mut uref_lookup,
        known_urefs,
        arguments,
        BTreeSet::new(),
        &account,
        base_key,
        BlockTime(block_time),
        deploy_hash,
        gas_limit,
        gas_counter,
        fn_store_id,
        address_generator,
        protocol_version,
        correlation_id,
        phase,
    );

    let wasm_bytes = test_support::read_wasm_file_bytes(wasm_file);
    let deploy_item = ExecutableDeployItem::ModuleBytes {
        module_bytes: wasm_bytes,
        args: Vec::new(),
    };
    let wasm_costs = WasmCosts::from_version(protocol_version).unwrap();
    let preprocessor = WasmiPreprocessor::new(wasm_costs);
    let parity_module = builder
        .get_engine_state()
        .get_module(
            tracking_copy,
            &deploy_item,
            &account,
            correlation_id,
            &preprocessor,
        )
        .expect("should get wasm module");

    let (instance, memory) =
        execution::instance_and_memory(parity_module.clone(), protocol_version)
            .expect("should be able to make wasm instance from module");

    let mut runtime = execution::Runtime::new(memory, parity_module, context);

    match instance.invoke_export("call", &[], &mut runtime) {
        Ok(_) => None,
        Err(e) => {
            if let Some(host_error) = e.as_host_error() {
                // `ret` Trap is a success; downcast and attempt to extract result
                let downcasted_error = host_error.downcast_ref::<execution::Error>().unwrap();
                match downcasted_error {
                    execution::Error::Ret(ref ret_urefs) => {
                        let effect = runtime.context().effect();
                        let urefs = ret_urefs.clone();

                        let value: T = bytesrepr::deserialize(runtime.result())
                            .expect("should deserialize return value");

                        Some((value, urefs, effect))
                    }

                    _ => None,
                }
            } else {
                None
            }
        }
    }
}
