use crate::support::test_support::{ExecuteRequestBuilder, InMemoryWasmTestBuilder};
use crate::test::{DEFAULT_ACCOUNT_ADDR, DEFAULT_GENESIS_CONFIG};
const CONTRACT_GET_BLOCKTIME: &str = "get_blocktime.wasm";

#[ignore]
#[test]
fn should_run_get_blocktime_contract() {
    let block_time: u64 = 42;
    let exec_request = ExecuteRequestBuilder::standard(
        DEFAULT_ACCOUNT_ADDR,
        CONTRACT_GET_BLOCKTIME,
        (block_time,),
    )
    .with_block_time(block_time)
    .build();
    InMemoryWasmTestBuilder::default()
        .run_genesis(&DEFAULT_GENESIS_CONFIG)
        .exec_with_exec_request(exec_request)
        .commit()
        .expect_success();
}
