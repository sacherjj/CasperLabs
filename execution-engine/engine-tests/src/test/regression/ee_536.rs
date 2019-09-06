use std::collections::HashMap;

use crate::support::test_support::{WasmTestBuilder, DEFAULT_BLOCK_TIME};

const GENESIS_ADDR: [u8; 32] = [6u8; 32];

#[ignore]
#[test]
fn should_run_ee_536_get_uref_regression_test() {
    // This test runs a contract that's after every call extends the same key with
    // more data
    let _result = WasmTestBuilder::default()
        .run_genesis(GENESIS_ADDR, HashMap::new())
        .exec(
            GENESIS_ADDR,
            "ee_536_regression.wasm",
            DEFAULT_BLOCK_TIME,
            1,
        )
        .expect_success()
        .commit()
        .finish();
}
