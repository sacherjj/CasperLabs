use engine_test_support::low_level::{
    ExecuteRequestBuilder, InMemoryWasmTestBuilder, DEFAULT_ACCOUNT_ADDR, DEFAULT_GENESIS_CONFIG,
};

const CONTRACT_DESERIALIZE_ERROR: &str = "deserialize_error.wasm";

#[ignore]
#[test]
fn should_not_fail_deserializing() {
    let exec_request = ExecuteRequestBuilder::standard(
        DEFAULT_ACCOUNT_ADDR,
        CONTRACT_DESERIALIZE_ERROR,
        (DEFAULT_ACCOUNT_ADDR,),
    )
    .build();
    let is_error = InMemoryWasmTestBuilder::default()
        .run_genesis(&DEFAULT_GENESIS_CONFIG)
        .exec(exec_request)
        .commit()
        .is_error();

    assert!(is_error);
}
