use engine_test_support::{
    internal::{
        DeployItemBuilder, ExecuteRequestBuilder, InMemoryWasmTestBuilder, DEFAULT_GENESIS_CONFIG,
        DEFAULT_PAYMENT,
    },
    DEFAULT_ACCOUNT_ADDR,
};
use types::{Key, SemVer};

const CONTRACT_COUNTER_CALL: &str = "counter_call.wasm";
const CONTRACT_COUNTER_DEFINE: &str = "counter_define.wasm";
const CONTRACT_COUNTER_WITH_HEADER: &str = "counter_with_header.wasm";
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

#[ignore]
#[test]
fn should_run_counter_with_headers_example_contract() {
    // This test runs a contract that's after every call extends the same key with
    // more data
    let exec_request_1 =
        ExecuteRequestBuilder::standard(DEFAULT_ACCOUNT_ADDR, CONTRACT_COUNTER_WITH_HEADER, ())
            .build();

    let exec_request_2 = {
        let deploy = DeployItemBuilder::new()
            .with_address(DEFAULT_ACCOUNT_ADDR)
            .with_stored_entry_point_at_version("counter", SemVer::new(1, 0, 0), "increment", (5,))
            .with_empty_payment_bytes((*DEFAULT_PAYMENT,))
            .with_authorization_keys(&[DEFAULT_ACCOUNT_ADDR])
            .with_deploy_hash([3; 32])
            .build();

        ExecuteRequestBuilder::new().push_deploy(deploy).build()
    };

    let exec_request_3 = {
        let deploy = DeployItemBuilder::new()
            .with_address(DEFAULT_ACCOUNT_ADDR)
            .with_stored_entry_point_at_version("counter", SemVer::new(1, 0, 0), "increment", (5,))
            .with_empty_payment_bytes((*DEFAULT_PAYMENT,))
            .with_authorization_keys(&[DEFAULT_ACCOUNT_ADDR])
            .with_deploy_hash([4; 32])
            .build();

        ExecuteRequestBuilder::new().push_deploy(deploy).build()
    };
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

    // Queries through active version
    let stored_value = builder
        .query(None, *counter_key, &["1.0.0", COUNT_KEY])
        .expect("should have counter value");
    let cl_value = stored_value.as_cl_value().expect("should be CLValue");
    let value: i32 = cl_value
        .clone()
        .into_t()
        .expect("should cast CLValue to integer");
    assert_eq!(value, 10);

    // Query non-existing version
    assert!(builder
        .query(None, *counter_key, &["100.200.300", COUNT_KEY])
        .is_err());
}
