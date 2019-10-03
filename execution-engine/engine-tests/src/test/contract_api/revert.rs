use crate::support::test_support::InMemoryWasmTestBuilder;
use crate::test::{DEFAULT_ACCOUNT_ADDR, DEFAULT_GENESIS_CONFIG};

const REVERT_WASM: &str = "revert.wasm";
const BLOCK_TIME: u64 = 42;

#[ignore]
#[test]
fn should_revert() {
    InMemoryWasmTestBuilder::default()
        .run_genesis(&DEFAULT_GENESIS_CONFIG)
        .exec(DEFAULT_ACCOUNT_ADDR, REVERT_WASM, BLOCK_TIME, [1u8; 32])
        .commit()
        .is_error();
}
