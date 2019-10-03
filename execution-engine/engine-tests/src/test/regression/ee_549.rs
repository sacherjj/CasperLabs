use crate::support::test_support::{InMemoryWasmTestBuilder, DEFAULT_BLOCK_TIME};
use crate::test::{DEFAULT_ACCOUNT_ADDR, DEFAULT_GENESIS_CONFIG};

#[ignore]
#[test]
fn should_run_ee_549_set_refund_regression() {
    let mut builder = InMemoryWasmTestBuilder::default();

    builder.run_genesis(&DEFAULT_GENESIS_CONFIG).exec(
        DEFAULT_ACCOUNT_ADDR,
        "ee_549_regression.wasm",
        DEFAULT_BLOCK_TIME,
        [1u8; 32],
    );

    // Execution should encounter an error because set_refund
    // is not allowed to be called during session execution.
    assert!(builder.is_error());
}
