use engine_test_support::{
    internal::{
        DeployItemBuilder, ExecuteRequestBuilder, InMemoryWasmTestBuilder, DEFAULT_PAYMENT,
        DEFAULT_RUN_GENESIS_REQUEST,
    },
    DEFAULT_ACCOUNT_ADDR,
};
use types::{runtime_args, Key, RuntimeArgs, SemVer};

const CONTRACT_HEADERS: &str = "contract_headers.wasm";
const METADATA_HASH_KEY: &str = "metadata_hash_key";
const METADATA_ACCESS_KEY: &str = "metadata_access_key";

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
                METADATA_HASH_KEY,
                SemVer::V1_0_0,
                "session_code_test",
                args,
            )
            .with_empty_payment_bytes((*DEFAULT_PAYMENT,))
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
                METADATA_HASH_KEY,
                SemVer::V1_0_0,
                "contract_code_test",
                args,
            )
            .with_empty_payment_bytes((*DEFAULT_PAYMENT,))
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
                METADATA_HASH_KEY,
                SemVer::V1_0_0,
                "add_new_key_as_session",
                args,
            )
            .with_empty_payment_bytes((*DEFAULT_PAYMENT,))
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
        .get(METADATA_HASH_KEY)
        .expect("should have contract metadata");
    let _access_uref = account
        .named_keys()
        .get(METADATA_ACCESS_KEY)
        .expect("should have metadata hash");

    let _foo = builder
        .get_exec_response(3)
        .expect("should have exec response");

    let account = builder
        .query(None, Key::Account(DEFAULT_ACCOUNT_ADDR), &[])
        .expect("should query account")
        .as_account()
        .cloned()
        .expect("should be account");

    let _new_key = account
        .named_keys()
        .get("new_key")
        .expect("new key should be there");
}
