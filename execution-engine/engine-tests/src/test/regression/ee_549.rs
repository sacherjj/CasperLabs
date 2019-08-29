use std::collections::HashMap;

use crate::support::test_support::{WasmTestBuilder, DEFAULT_BLOCK_TIME};

const GENESIS_ADDR: [u8; 32] = [6u8; 32];

#[ignore]
#[test]
fn should_run_ee_549_set_refund_regression() {
    let mut builder = WasmTestBuilder::default();

    builder.run_genesis(GENESIS_ADDR, HashMap::new()).exec(
        GENESIS_ADDR,
        "ee_549_regression.wasm",
        DEFAULT_BLOCK_TIME,
        1,
    );

    // Execution should encounter an error because set_refund
    // is not allowed to be called during session execution.
    assert!(builder.is_error());
}
