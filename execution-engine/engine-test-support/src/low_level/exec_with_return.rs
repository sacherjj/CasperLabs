use std::{cell::RefCell, collections::BTreeSet, convert::TryInto, rc::Rc};

use contract::args_parser::ArgsParser;
use engine_core::{
    engine_state::{
        executable_deploy_item::ExecutableDeployItem, execution_effect::ExecutionEffect,
        EngineState,
    },
    execution::{self, AddressGenerator},
    runtime_context::RuntimeContext,
};
use engine_grpc_server::engine_server::ipc_grpc::ExecutionEngineService;
use engine_shared::{gas::Gas, newtypes::CorrelationId};
use engine_storage::{global_state::StateProvider, protocol_data::ProtocolData};
use engine_wasm_prep::Preprocessor;
use types::{
    bytesrepr::FromBytes, BlockTime, CLTyped, CLValue, Key, Phase, ProtocolVersion, URef, U512,
};

use crate::low_level::{utils, WasmTestBuilder, DEFAULT_WASM_COSTS};

const INIT_FN_STORE_ID: u32 = 0;

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
    args: impl ArgsParser,
    extra_urefs: Vec<URef>,
) -> Option<(T, Vec<URef>, ExecutionEffect)>
where
    S: StateProvider,
    S::Error: Into<execution::Error>,
    EngineState<S>: ExecutionEngineService,
    T: FromBytes + CLTyped,
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
    let gas_limit = Gas::new(U512::from(std::u64::MAX));
    let protocol_version = ProtocolVersion::V1_0_0;
    let correlation_id = CorrelationId::new();
    let arguments: Vec<CLValue> = args.parse().expect("should be able to serialize args");
    let base_key = Key::Account(address);

    let account = builder.get_account(address).expect("should find account");

    let mut named_keys = account.named_keys().clone();

    let access_rights = {
        let mut ret = execution::extract_access_rights_from_keys(named_keys.values().cloned());
        let extras = execution::extract_access_rights_from_urefs(extra_urefs.into_iter());
        ret.extend(extras.into_iter());
        ret
    };

    let context = RuntimeContext::new(
        Rc::clone(&tracking_copy),
        &mut named_keys,
        access_rights,
        arguments,
        BTreeSet::new(),
        &account,
        base_key,
        BlockTime::new(block_time),
        deploy_hash,
        gas_limit,
        gas_counter,
        fn_store_id,
        address_generator,
        protocol_version,
        correlation_id,
        phase,
        ProtocolData::default(),
    );

    let wasm_bytes = utils::read_wasm_file_bytes(wasm_file);
    let deploy_item = ExecutableDeployItem::ModuleBytes {
        module_bytes: wasm_bytes,
        args: Vec::new(),
    };

    let wasm_costs = *DEFAULT_WASM_COSTS;

    let preprocessor = Preprocessor::new(wasm_costs);
    let parity_module = builder
        .get_engine_state()
        .get_module(
            tracking_copy,
            &deploy_item,
            &account,
            correlation_id,
            &preprocessor,
            &protocol_version,
        )
        .expect("should get wasm module");

    let (instance, memory) =
        execution::instance_and_memory(parity_module.clone(), protocol_version)
            .expect("should be able to make wasm instance from module");

    let mut runtime = execution::Runtime::new(Default::default(), memory, parity_module, context);

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

                        let value: T = runtime
                            .take_host_buf()
                            .expect("should have return value in the host_buf")
                            .into_t()
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
