use engine_test_support::low_level::{
    ExecuteRequestBuilder, InMemoryWasmTestBuilder, DEFAULT_ACCOUNT_ADDR, DEFAULT_GENESIS_CONFIG,
};

const CONTRACT_EE_536_REGRESSION: &str = "ee_536_regression.wasm";

#[ignore]
#[test]
fn should_run_ee_536_get_uref_regression_test() {
    // This test runs a contract that's after every call extends the same key with
    // more data
    let exec_request =
        ExecuteRequestBuilder::standard(DEFAULT_ACCOUNT_ADDR, CONTRACT_EE_536_REGRESSION, ())
            .build();

    let _result = InMemoryWasmTestBuilder::default()
        .run_genesis(&DEFAULT_GENESIS_CONFIG)
        .exec(exec_request)
        .expect_success()
        .commit()
        .finish();
}
