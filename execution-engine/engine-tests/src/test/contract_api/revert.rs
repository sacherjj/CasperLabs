use engine_test_support::{
    low_level::{ExecuteRequestBuilder, InMemoryWasmTestBuilder, DEFAULT_GENESIS_CONFIG},
    DEFAULT_ACCOUNT_ADDR,
};

const REVERT_WASM: &str = "revert.wasm";

#[ignore]
#[test]
fn should_revert() {
    let exec_request =
        ExecuteRequestBuilder::standard(DEFAULT_ACCOUNT_ADDR, REVERT_WASM, ()).build();
    InMemoryWasmTestBuilder::default()
        .run_genesis(&DEFAULT_GENESIS_CONFIG)
        .exec(exec_request)
        .commit()
        .is_error();
}
