use std::collections::HashMap;

use crate::support::test_support::WasmTestBuilder;

const GENESIS_ADDR: [u8; 32] = [7u8; 32];

#[ignore]
#[test]
fn should_run_get_blocktime_contract() {
    let block_time: u64 = 42;

    WasmTestBuilder::default()
        .run_genesis(GENESIS_ADDR, HashMap::new())
        .exec_with_args(
            GENESIS_ADDR,
            "get_blocktime.wasm",
            block_time,
            1,
            (block_time,), // passing this to contract to test assertion
        )
        .commit()
        .expect_success();
}
