use std::collections::HashMap;

use crate::support::test_support::WasmTestBuilder;

const GENESIS_ADDR: [u8; 32] = [7u8; 32];
const REVERT_WASM: &str = "revert.wasm";
const BLOCK_TIME: u64 = 42;

#[ignore]
#[test]
fn should_revert() {
    WasmTestBuilder::default()
        .run_genesis(GENESIS_ADDR, HashMap::new())
        .exec(GENESIS_ADDR, REVERT_WASM, BLOCK_TIME, 1)
        .commit()
        .is_error();
}
