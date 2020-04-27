use engine_test_support::{
    internal::{
        DeployItemBuilder, ExecuteRequestBuilder, InMemoryWasmTestBuilder, DEFAULT_PAYMENT,
        DEFAULT_RUN_GENESIS_REQUEST,
    },
    DEFAULT_ACCOUNT_ADDR,
};
use types::{runtime_args, Key, RuntimeArgs, SemVer};

const CONTRACT_COUNTER_CALL: &str = "counter_call.wasm";
const CONTRACT_COUNTER_DEFINE: &str = "counter_define.wasm";
const CONTRACT_COUNTER_WITH_HEADER: &str = "counter_with_header.wasm";
const COUNTER_KEY: &str = "counter";
const COUNT_KEY: &str = "count";
const STEP_1: i32 = 5;
const STEP_2: i32 = 6;
const STEP_3: i32 = 42;

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
        .run_genesis(&DEFAULT_RUN_GENESIS_REQUEST)
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
        let args = runtime_args! {
            "step" => STEP_1,
        };
        let deploy = DeployItemBuilder::new()
            .with_address(DEFAULT_ACCOUNT_ADDR)
            .with_stored_versioned_contract_by_name("counter", SemVer::V1_0_0, "increment", args)
            .with_empty_payment_bytes((*DEFAULT_PAYMENT,))
            .with_authorization_keys(&[DEFAULT_ACCOUNT_ADDR])
            .with_deploy_hash([3; 32])
            .build();

        ExecuteRequestBuilder::new().push_deploy(deploy).build()
    };

    let exec_request_3 = {
        let args = runtime_args! {
            "step" => STEP_2,
        };
        // args.insert("step".to_owned(), CLValue::from_t(5i32).unwrap());
        let deploy = DeployItemBuilder::new()
            .with_address(DEFAULT_ACCOUNT_ADDR)
            .with_stored_versioned_contract_by_name("counter", SemVer::V1_0_0, "increment", args)
            .with_empty_payment_bytes((*DEFAULT_PAYMENT,))
            .with_authorization_keys(&[DEFAULT_ACCOUNT_ADDR])
            .with_deploy_hash([4; 32])
            .build();

        ExecuteRequestBuilder::new().push_deploy(deploy).build()
    };

    let exec_request_4 = {
        let args = runtime_args! {
            "this parameter is unused" => 0,
            "step" => STEP_3,
        };
        let deploy = DeployItemBuilder::new()
            .with_address(DEFAULT_ACCOUNT_ADDR)
            .with_stored_versioned_contract_by_name(
                "counter",
                SemVer::V1_0_0,
                "counter_caller",
                args,
            )
            .with_empty_payment_bytes((*DEFAULT_PAYMENT,))
            .with_authorization_keys(&[DEFAULT_ACCOUNT_ADDR])
            .with_deploy_hash([4; 32])
            .build();

        ExecuteRequestBuilder::new().push_deploy(deploy).build()
    };

    let exec_request_5 = {
        let args = runtime_args! {};
        let deploy = DeployItemBuilder::new()
            .with_address(DEFAULT_ACCOUNT_ADDR)
            .with_stored_versioned_contract_by_name(
                "counter",
                SemVer::V1_0_0,
                "counter_remover",
                args,
            )
            .with_empty_payment_bytes((*DEFAULT_PAYMENT,))
            .with_authorization_keys(&[DEFAULT_ACCOUNT_ADDR])
            .with_deploy_hash([5; 32])
            .build();

        ExecuteRequestBuilder::new().push_deploy(deploy).build()
    };

    let mut builder = InMemoryWasmTestBuilder::default();

    builder
        .run_genesis(&DEFAULT_RUN_GENESIS_REQUEST)
        .exec(exec_request_1)
        .expect_success()
        .commit();

    builder.exec(exec_request_2).expect_success().commit();

    builder.exec(exec_request_3).expect_success().commit();

    builder.exec(exec_request_4).expect_success().commit();

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
    assert_eq!(value, STEP_1 + STEP_2 + STEP_3);

    // Query non-existing version
    assert!(builder
        .query(None, *counter_key, &["100.200.300", COUNT_KEY])
        .is_err());

    builder.exec(exec_request_5).expect_success().commit();

    // Querying removed version should raise error
    assert!(builder
        .query(None, *counter_key, &["1.0.0", COUNT_KEY])
        .is_err());
}

#[ignore]
#[test]
fn should_run_counter_with_headers_example_contract_by_hash() {
    // This test runs a contract that's after every call extends the same key with
    // more data
    let exec_request_1 =
        ExecuteRequestBuilder::standard(DEFAULT_ACCOUNT_ADDR, CONTRACT_COUNTER_WITH_HEADER, ())
            .build();

    let mut builder = InMemoryWasmTestBuilder::default();

    builder
        .run_genesis(&DEFAULT_RUN_GENESIS_REQUEST)
        .exec(exec_request_1)
        .expect_success()
        .commit();

    let account_value = builder
        .query(None, Key::Account(DEFAULT_ACCOUNT_ADDR), &[])
        .expect("should query account");
    let account = account_value.as_account().expect("should be account");
    let counter_key = account
        .named_keys()
        .get(COUNTER_KEY)
        .expect("should have counter key");

    let counter_hash = counter_key.into_hash().expect("should be hash");

    let exec_request_2 = {
        let args = runtime_args! {
            "step" => STEP_1,
        };
        let deploy = DeployItemBuilder::new()
            .with_address(DEFAULT_ACCOUNT_ADDR)
            .with_stored_versioned_contract_by_hash(counter_hash, SemVer::V1_0_0, "increment", args)
            .with_empty_payment_bytes((*DEFAULT_PAYMENT,))
            .with_authorization_keys(&[DEFAULT_ACCOUNT_ADDR])
            .with_deploy_hash([3; 32])
            .build();

        ExecuteRequestBuilder::new().push_deploy(deploy).build()
    };

    let exec_request_3 = {
        let args = runtime_args! {
            "step" => STEP_2,
        };
        // args.insert("step".to_owned(), CLValue::from_t(5i32).unwrap());
        let deploy = DeployItemBuilder::new()
            .with_address(DEFAULT_ACCOUNT_ADDR)
            .with_stored_versioned_contract_by_hash(counter_hash, SemVer::V1_0_0, "increment", args)
            .with_empty_payment_bytes((*DEFAULT_PAYMENT,))
            .with_authorization_keys(&[DEFAULT_ACCOUNT_ADDR])
            .with_deploy_hash([4; 32])
            .build();

        ExecuteRequestBuilder::new().push_deploy(deploy).build()
    };

    let exec_request_4 = {
        let args = runtime_args! {
            "this parameter is unused" => 0,
            "step" => STEP_3,
        };
        let deploy = DeployItemBuilder::new()
            .with_address(DEFAULT_ACCOUNT_ADDR)
            .with_stored_versioned_contract_by_hash(
                counter_hash,
                SemVer::V1_0_0,
                "counter_caller",
                args,
            )
            .with_empty_payment_bytes((*DEFAULT_PAYMENT,))
            .with_authorization_keys(&[DEFAULT_ACCOUNT_ADDR])
            .with_deploy_hash([4; 32])
            .build();

        ExecuteRequestBuilder::new().push_deploy(deploy).build()
    };

    let exec_request_5 = {
        let args = runtime_args! {};
        let deploy = DeployItemBuilder::new()
            .with_address(DEFAULT_ACCOUNT_ADDR)
            .with_stored_versioned_contract_by_hash(
                counter_hash,
                SemVer::V1_0_0,
                "counter_remover",
                args,
            )
            .with_empty_payment_bytes((*DEFAULT_PAYMENT,))
            .with_authorization_keys(&[DEFAULT_ACCOUNT_ADDR])
            .with_deploy_hash([5; 32])
            .build();

        ExecuteRequestBuilder::new().push_deploy(deploy).build()
    };

    builder.exec(exec_request_2).expect_success().commit();

    builder.exec(exec_request_3).expect_success().commit();

    builder.exec(exec_request_4).expect_success().commit();

    // Queries through active version
    let stored_value = builder
        .query(None, *counter_key, &["1.0.0", COUNT_KEY])
        .expect("should have counter value");
    let cl_value = stored_value.as_cl_value().expect("should be CLValue");
    let value: i32 = cl_value
        .clone()
        .into_t()
        .expect("should cast CLValue to integer");
    assert_eq!(value, STEP_1 + STEP_2 + STEP_3);

    // Query non-existing version
    assert!(builder
        .query(None, *counter_key, &["100.200.300", COUNT_KEY])
        .is_err());

    builder.exec(exec_request_5).expect_success().commit();

    // Querying removed version should raise error
    assert!(builder
        .query(None, *counter_key, &["1.0.0", COUNT_KEY])
        .is_err());
}
