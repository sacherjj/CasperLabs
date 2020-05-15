use engine_test_support::{
    internal::{ExecuteRequestBuilder, InMemoryWasmTestBuilder, DEFAULT_RUN_GENESIS_REQUEST},
    DEFAULT_ACCOUNT_ADDR,
};
use types::{account::Weight, runtime_args, RuntimeArgs};

const CONTRACT_EE_539_REGRESSION: &str = "ee_539_regression.wasm";

#[ignore]
#[test]
fn should_run_ee_539_serialize_action_thresholds_regression() {
    // This test runs a contract that's after every call extends the same key with
    // more data
    let exec_request = ExecuteRequestBuilder::standard(
        DEFAULT_ACCOUNT_ADDR,
        CONTRACT_EE_539_REGRESSION,
        runtime_args! { "key_management_threshold" => Weight::new(4), "deploy_threshold" => Weight::new(3) },
    )
    .build();

    let _result = InMemoryWasmTestBuilder::default()
        .run_genesis(&DEFAULT_RUN_GENESIS_REQUEST)
        .exec(exec_request)
        .expect_success()
        .commit()
        .finish();
}
