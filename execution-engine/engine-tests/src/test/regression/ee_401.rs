use std::collections::HashMap;

use grpc::RequestOptions;

use contract_ffi::value::account::PublicKey;
use engine_core::engine_state::EngineState;
use engine_grpc_server::engine_server::ipc_grpc::ExecutionEngineService;
use engine_storage::global_state::in_memory::InMemoryGlobalState;

use crate::support::test_support::DEFAULT_BLOCK_TIME;

const GENESIS_ADDR: [u8; 32] = [0u8; 32];

#[ignore]
#[test]
fn should_execute_contracts_which_provide_extra_urefs() {
    let _result = test_support::WasmTestBuilder::default()
        .run_genesis(GENESIS_ADDR, HashMap::new())
        .exec(
            GENESIS_ADDR,
            "ee_401_regression.wasm",
            DEFAULT_BLOCK_TIME,
            [1u8; 32],
        )
        .expect_success()
        .commit()
        .exec_with_args(
            GENESIS_ADDR,
            "ee_401_regression_call.wasm",
            DEFAULT_BLOCK_TIME,
            [2u8; 32],
            (PublicKey::new(GENESIS_ADDR),),
        )
        .expect_success()
        .commit()
        .finish();
}
