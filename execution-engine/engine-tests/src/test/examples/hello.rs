use engine_test_support::{
    internal::{ExecuteRequestBuilder, InMemoryWasmTestBuilder, DEFAULT_GENESIS_CONFIG},
    DEFAULT_ACCOUNT_ADDR,
};
use types::Key;

const HELLO_CALL: &str = "hello_name_call.wasm";
const HELLO_DEFINE: &str = "hello_name_define.wasm";
const HELLO_NAME_METADATA_KEY: &str = "hello_name_metadata";
const HELLOWORLD_KEY: &str = "helloworld";

#[ignore]
#[test]
fn should_run_hello_example_contract() {
    let exec_request_1 =
        ExecuteRequestBuilder::standard(DEFAULT_ACCOUNT_ADDR, HELLO_DEFINE, ()).build();

    let mut builder = InMemoryWasmTestBuilder::default();

    builder
        .run_genesis(&DEFAULT_GENESIS_CONFIG)
        .exec(exec_request_1)
        .expect_success()
        .commit();

    let account_value = builder
        .query(None, Key::Account(DEFAULT_ACCOUNT_ADDR), &[])
        .expect("should query account");
    let account = account_value
        .as_account()
        .cloned()
        .expect("should be account");

    let metadata_key = account
        .named_keys()
        .get(HELLO_NAME_METADATA_KEY)
        .cloned()
        .expect("should have metadata");

    let exec_request_2 =
        ExecuteRequestBuilder::standard(DEFAULT_ACCOUNT_ADDR, HELLO_CALL, (metadata_key,)).build();

    builder.exec(exec_request_2).expect_success().commit();

    let stored_value = builder
        .query(None, Key::Account(DEFAULT_ACCOUNT_ADDR), &[HELLOWORLD_KEY])
        .expect("should query");
    let value = stored_value
        .as_cl_value()
        .cloned()
        .expect("should be clvalue");

    let message: String = value.into_t().expect("should be string");
    assert_eq!(message, "Hello, World");
}
