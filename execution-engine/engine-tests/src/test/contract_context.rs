use assert_matches::assert_matches;
use engine_core::{engine_state::Error, execution};
use engine_test_support::{
    internal::{
        DeployItemBuilder, ExecuteRequestBuilder, InMemoryWasmTestBuilder, DEFAULT_PAYMENT,
        DEFAULT_RUN_GENESIS_REQUEST,
    },
    DEFAULT_ACCOUNT_ADDR,
};
use types::{contracts::CONTRACT_INITIAL_VERSION, runtime_args, Key, RuntimeArgs};

const CONTRACT_HEADERS: &str = "contract_context.wasm";
const PACKAGE_HASH_KEY: &str = "package_hash_key";
const PACKAGE_ACCESS_KEY: &str = "package_access_key";
const SESSION_CODE_TEST: &str = "session_code_test";
const CONTRACT_CODE_TEST: &str = "contract_code_test";
const ADD_NEW_KEY_AS_SESSION: &str = "add_new_key_as_session";
const NEW_KEY: &str = "new_key";
const SESSION_CODE_CALLER_AS_CONTRACT: &str = "session_code_caller_as_contract";
const ARG_AMOUNT: &str = "amount";

#[ignore]
#[test]
fn should_calling_session_and_contract_has_correct_context() {
    // This test runs a contract that's after every call extends the same key with
    // more data
    let exec_request_1 =
        ExecuteRequestBuilder::standard(DEFAULT_ACCOUNT_ADDR, CONTRACT_HEADERS, ()).build();

    let exec_request_2 = {
        let args = runtime_args! {};
        let deploy = DeployItemBuilder::new()
            .with_address(DEFAULT_ACCOUNT_ADDR)
            .with_stored_versioned_contract_by_name(
                PACKAGE_HASH_KEY,
                CONTRACT_INITIAL_VERSION,
                SESSION_CODE_TEST,
                args,
            )
            .with_empty_payment_bytes(runtime_args! { ARG_AMOUNT => *DEFAULT_PAYMENT, })
            .with_authorization_keys(&[DEFAULT_ACCOUNT_ADDR])
            .with_deploy_hash([3; 32])
            .build();

        ExecuteRequestBuilder::new().push_deploy(deploy).build()
    };

    let exec_request_3 = {
        let args = runtime_args! {};
        let deploy = DeployItemBuilder::new()
            .with_address(DEFAULT_ACCOUNT_ADDR)
            .with_stored_versioned_contract_by_name(
                PACKAGE_HASH_KEY,
                CONTRACT_INITIAL_VERSION,
                CONTRACT_CODE_TEST,
                args,
            )
            .with_empty_payment_bytes(runtime_args! { ARG_AMOUNT => *DEFAULT_PAYMENT, })
            .with_authorization_keys(&[DEFAULT_ACCOUNT_ADDR])
            .with_deploy_hash([3; 32])
            .build();

        ExecuteRequestBuilder::new().push_deploy(deploy).build()
    };

    let exec_request_4 = {
        let args = runtime_args! {};
        let deploy = DeployItemBuilder::new()
            .with_address(DEFAULT_ACCOUNT_ADDR)
            .with_stored_versioned_contract_by_name(
                PACKAGE_HASH_KEY,
                CONTRACT_INITIAL_VERSION,
                ADD_NEW_KEY_AS_SESSION,
                args,
            )
            .with_empty_payment_bytes(runtime_args! { ARG_AMOUNT => *DEFAULT_PAYMENT, })
            .with_authorization_keys(&[DEFAULT_ACCOUNT_ADDR])
            .with_deploy_hash([4; 32])
            .build();

        ExecuteRequestBuilder::new().push_deploy(deploy).build()
    };
    let mut builder = InMemoryWasmTestBuilder::default();

    builder.run_genesis(&DEFAULT_RUN_GENESIS_REQUEST);

    builder.exec(exec_request_1).expect_success().commit();

    builder.exec(exec_request_2).expect_success().commit();

    builder.exec(exec_request_3).expect_success().commit();

    builder.exec(exec_request_4).expect_success().commit();

    let account = builder
        .query(None, Key::Account(DEFAULT_ACCOUNT_ADDR), &[])
        .expect("should query account")
        .as_account()
        .cloned()
        .expect("should be account");

    let _metadata_hash = account
        .named_keys()
        .get(PACKAGE_HASH_KEY)
        .expect("should have contract metadata");
    let _access_uref = account
        .named_keys()
        .get(PACKAGE_ACCESS_KEY)
        .expect("should have metadata hash");

    let account = builder
        .query(None, Key::Account(DEFAULT_ACCOUNT_ADDR), &[])
        .expect("should query account")
        .as_account()
        .cloned()
        .expect("should be account");

    let _new_key = account
        .named_keys()
        .get(NEW_KEY)
        .expect("new key should be there");
}

#[ignore]
#[test]
fn should_not_call_session_from_contract() {
    // This test runs a contract that's after every call extends the same key with
    // more data
    let exec_request_1 =
        ExecuteRequestBuilder::standard(DEFAULT_ACCOUNT_ADDR, CONTRACT_HEADERS, ()).build();

    let mut builder = InMemoryWasmTestBuilder::default();

    builder.run_genesis(&DEFAULT_RUN_GENESIS_REQUEST);

    builder.exec(exec_request_1).expect_success().commit();

    let account = builder
        .query(None, Key::Account(DEFAULT_ACCOUNT_ADDR), &[])
        .expect("should query account")
        .as_account()
        .cloned()
        .expect("should be account");

    let contract_package_hash = account
        .named_keys()
        .get(PACKAGE_HASH_KEY)
        .cloned()
        .expect("should have contract package");

    let exec_request_2 = {
        let args = runtime_args! {
            PACKAGE_HASH_KEY => contract_package_hash,
        };
        let deploy = DeployItemBuilder::new()
            .with_address(DEFAULT_ACCOUNT_ADDR)
            .with_stored_versioned_contract_by_name(
                PACKAGE_HASH_KEY,
                CONTRACT_INITIAL_VERSION,
                SESSION_CODE_CALLER_AS_CONTRACT,
                args,
            )
            .with_empty_payment_bytes(runtime_args! { ARG_AMOUNT => *DEFAULT_PAYMENT, })
            .with_authorization_keys(&[DEFAULT_ACCOUNT_ADDR])
            .with_deploy_hash([3; 32])
            .build();

        ExecuteRequestBuilder::new().push_deploy(deploy).build()
    };

    builder.exec(exec_request_2).commit();

    let response = builder
        .get_exec_responses()
        .last()
        .expect("should have last response");
    assert_eq!(response.len(), 1);
    let exec_response = response.last().expect("should have response");
    let error = exec_response.as_error().expect("should have error");
    assert_matches!(error, Error::Exec(execution::Error::InvalidContext));
}
