use crate::support::test_support::{ExecuteRequestBuilder, InMemoryWasmTestBuilder};
use crate::test::{DEFAULT_ACCOUNT_ADDR, DEFAULT_GENESIS_CONFIG};

const CONTRACT_DESERIALIZE_ERROR: &str = "deserialize_error";

#[ignore]
#[test]
fn should_not_fail_deserializing() {
    let exec_request = {
        let contract_name = format!("{}.wasm", CONTRACT_DESERIALIZE_ERROR);
        ExecuteRequestBuilder::standard(
            DEFAULT_ACCOUNT_ADDR,
            &contract_name,
            (DEFAULT_ACCOUNT_ADDR,),
        )
    };
    let is_error = InMemoryWasmTestBuilder::default()
        .run_genesis(&DEFAULT_GENESIS_CONFIG)
        .exec_with_exec_request(exec_request)
        .commit()
        .is_error();

    assert!(is_error);
}
