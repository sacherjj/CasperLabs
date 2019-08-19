extern crate casperlabs_engine_grpc_server;
extern crate contract_ffi;
extern crate engine_core;
extern crate engine_shared;
extern crate engine_storage;
extern crate grpc;

use std::collections::HashMap;

use contract_ffi::execution::Phase;
use test_support::{WasmTestBuilder, DEFAULT_BLOCK_TIME};

#[allow(dead_code)]
mod test_support;

const GENESIS_ADDR: [u8; 32] = [7u8; 32];

#[ignore]
#[test]
fn should_run_get_phase_contract() {
    WasmTestBuilder::default()
        .run_genesis(GENESIS_ADDR, HashMap::new())
        .exec_with_args(
            GENESIS_ADDR,
            "get_phase.wasm",
            DEFAULT_BLOCK_TIME,
            1,
            Phase::Session,
        )
        .commit()
        .expect_success();
}
