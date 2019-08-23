extern crate casperlabs_engine_grpc_server;
extern crate contract_ffi;
extern crate engine_core;
extern crate engine_shared;
extern crate engine_storage;
extern crate grpc;

use std::collections::HashMap;

use test_support::{WasmTestBuilder, DEFAULT_BLOCK_TIME};

#[allow(dead_code)]
mod test_support;

const GENESIS_ADDR: [u8; 32] = [6u8; 32];

#[ignore]
#[test]
fn should_run_ee_549_set_refund_regression() {
    let mut builder = WasmTestBuilder::default();

    builder.run_genesis(GENESIS_ADDR, HashMap::new()).exec(
        GENESIS_ADDR,
        "ee_549_regression.wasm",
        DEFAULT_BLOCK_TIME,
        [1u8; 32],
    );

    // Execution should encounter an error because set_refund
    // is not allowed to be called during session execution.
    assert!(builder.is_error());
}
