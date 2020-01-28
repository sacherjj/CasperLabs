use engine_test_support::low_level::{
    ExecuteRequestBuilder, InMemoryWasmTestBuilder, DEFAULT_ACCOUNT_ADDR, DEFAULT_GENESIS_CONFIG,
};
use types::key::Key;

const CONTRACT_COUNTER_CALL: &str = "counter_call.wasm";
const CONTRACT_COUNTER_DEFINE: &str = "counter_define.wasm";
const COUNTER_KEY: &str = "counter";
const COUNT_KEY: &str = "count";

#[ignore]
#[test]
fn should_run_counter_example_contract() {
    // This test runs a contract that's after every call extends the same key with
    // more data
    let exec_request_1 =
        ExecuteRequestBuilder::standard(DEFAULT_ACCOUNT_ADDR, CONTRACT_COUNTER_DEFINE, ()).build();

    let exec_request_2 =
        ExecuteRequestBuilder::standard(DEFAULT_ACCOUNT_ADDR, CONTRACT_COUNTER_CALL, ()).build();

    let exec_request_3 =
        ExecuteRequestBuilder::standard(DEFAULT_ACCOUNT_ADDR, CONTRACT_COUNTER_CALL, ()).build();

    let mut builder = InMemoryWasmTestBuilder::default();

    builder
        .run_genesis(&DEFAULT_GENESIS_CONFIG)
        .exec(exec_request_1)
        .expect_success()
        .commit();

    builder.exec(exec_request_2).expect_success().commit();

    builder.exec(exec_request_3).expect_success().commit();

    let account_value = builder
        .query(None, Key::Account(DEFAULT_ACCOUNT_ADDR), &[])
        .expect("should query account");
    let account = account_value.as_account().expect("should be account");
    let counter_key = account
        .named_keys()
        .get(COUNTER_KEY)
        .expect("should have counter key");
    let stored_value = builder
        .query(None, *counter_key, &[COUNT_KEY])
        .expect("should have counter value");
    let cl_value = stored_value.as_cl_value().expect("should be CLValue");
    let value: i32 = cl_value
        .clone()
        .into_t()
        .expect("should cast CLValue to integer");
    assert_eq!(value, 2);
}
