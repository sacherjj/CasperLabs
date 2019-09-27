use crate::support::test_support::{InMemoryWasmTestBuilder, DEFAULT_BLOCK_TIME};
use crate::test::{DEFAULT_ACCOUNT_ADDR, DEFAULT_GENESIS_CONFIG};

#[ignore]
#[test]
fn should_run_ee_536_get_uref_regression_test() {
    // This test runs a contract that's after every call extends the same key with
    // more data
    let _result = InMemoryWasmTestBuilder::default()
        .run_genesis(&DEFAULT_GENESIS_CONFIG)
        .exec(
            DEFAULT_ACCOUNT_ADDR,
            "ee_536_regression.wasm",
            DEFAULT_BLOCK_TIME,
            [1u8; 32],
        )
        .expect_success()
        .commit()
        .finish();
}
