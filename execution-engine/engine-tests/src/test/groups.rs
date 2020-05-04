use assert_matches::assert_matches;
use lazy_static::lazy_static;

use engine_core::{engine_state::Error, execution};
use engine_test_support::{
    internal::{
        DeployItemBuilder, ExecuteRequestBuilder, InMemoryWasmTestBuilder, DEFAULT_PAYMENT,
        DEFAULT_RUN_GENESIS_REQUEST,
    },
    DEFAULT_ACCOUNT_ADDR,
};
use types::{account::PublicKey, runtime_args, Key, RuntimeArgs, SemVer, U512};

const CONTRACT_GROUPS: &str = "groups.wasm";
const METADATA_HASH_KEY: &str = "metadata_hash_key";
const METADATA_ACCESS_KEY: &str = "metadata_access_key";
const RESTRICTED_SESSION: &str = "restricted_session";
const RESTRICTED_CONTRACT: &str = "restricted_contract";
const RESTRICTED_SESSION_CALLER: &str = "restricted_session_caller";
const UNRESTRICTED_CONTRACT_CALLER: &str = "unrestricted_contract_caller";
const METADATA_HASH_ARG: &str = "metadata_hash";
const ACCOUNT_1_ADDR: PublicKey = PublicKey::ed25519_from([1u8; 32]);
const CONTRACT_TRANSFER_TO_ACCOUNT: &str = "transfer_to_account_u512.wasm";
const RESTRICTED_CONTRACT_CALLER_AS_SESSION: &str = "restricted_contract_caller_as_session";

lazy_static! {
    static ref TRANSFER_1_AMOUNT: U512 = U512::from(250_000_000) + 1000;
    static ref TRANSFER_2_AMOUNT: U512 = U512::from(750);
    static ref TRANSFER_2_AMOUNT_WITH_ADV: U512 = *DEFAULT_PAYMENT + *TRANSFER_2_AMOUNT;
    static ref TRANSFER_TOO_MUCH: U512 = U512::from(u64::max_value());
    static ref ACCOUNT_1_INITIAL_BALANCE: U512 = *DEFAULT_PAYMENT;
}
#[ignore]
#[test]
fn should_call_group_restricted_session() {
    // This test runs a contract that's after every call extends the same key with
    // more data
    let exec_request_1 =
        ExecuteRequestBuilder::standard(DEFAULT_ACCOUNT_ADDR, CONTRACT_GROUPS, ()).build();

    let mut builder = InMemoryWasmTestBuilder::default();

    builder.run_genesis(&DEFAULT_RUN_GENESIS_REQUEST);

    builder.exec(exec_request_1).expect_success().commit();

    let account = builder
        .query(None, Key::Account(DEFAULT_ACCOUNT_ADDR), &[])
        .expect("should query account")
        .as_account()
        .cloned()
        .expect("should be account");

    let metadata_hash = account
        .named_keys()
        .get(METADATA_HASH_KEY)
        .expect("should have contract metadata");
    let _access_uref = account
        .named_keys()
        .get(METADATA_ACCESS_KEY)
        .expect("should have metadata hash");

    let exec_request_2 = {
        // This inserts metadata as an argument because this test
        // can work from different accounts which might not have the same keys in their session
        // code.
        let args = runtime_args! {
            METADATA_HASH_ARG => *metadata_hash,
        };
        let deploy = DeployItemBuilder::new()
            .with_address(DEFAULT_ACCOUNT_ADDR)
            .with_stored_versioned_contract_by_name(
                METADATA_HASH_KEY,
                SemVer::V1_0_0,
                RESTRICTED_SESSION,
                args,
            )
            .with_empty_payment_bytes((*DEFAULT_PAYMENT,))
            .with_authorization_keys(&[DEFAULT_ACCOUNT_ADDR])
            .with_deploy_hash([3; 32])
            .build();

        ExecuteRequestBuilder::new().push_deploy(deploy).build()
    };

    builder.exec(exec_request_2).expect_success().commit();

    let _account = builder
        .query(None, Key::Account(DEFAULT_ACCOUNT_ADDR), &[])
        .expect("should query account")
        .as_account()
        .cloned()
        .expect("should be account");
}

#[ignore]
#[test]
fn should_call_group_restricted_session_caller() {
    // This test runs a contract that's after every call extends the same key with
    // more data
    let exec_request_1 =
        ExecuteRequestBuilder::standard(DEFAULT_ACCOUNT_ADDR, CONTRACT_GROUPS, ()).build();

    let mut builder = InMemoryWasmTestBuilder::default();

    builder.run_genesis(&DEFAULT_RUN_GENESIS_REQUEST);

    builder.exec(exec_request_1).expect_success().commit();

    let account = builder
        .query(None, Key::Account(DEFAULT_ACCOUNT_ADDR), &[])
        .expect("should query account")
        .as_account()
        .cloned()
        .expect("should be account");

    let metadata_hash = account
        .named_keys()
        .get(METADATA_HASH_KEY)
        .expect("should have contract metadata");
    let _access_uref = account
        .named_keys()
        .get(METADATA_ACCESS_KEY)
        .expect("should have metadata hash");

    let exec_request_2 = {
        let args = runtime_args! {
            METADATA_HASH_ARG => *metadata_hash,
        };
        let deploy = DeployItemBuilder::new()
            .with_address(DEFAULT_ACCOUNT_ADDR)
            .with_stored_versioned_contract_by_name(
                METADATA_HASH_KEY,
                SemVer::V1_0_0,
                RESTRICTED_SESSION_CALLER,
                args,
            )
            .with_empty_payment_bytes((*DEFAULT_PAYMENT,))
            .with_authorization_keys(&[DEFAULT_ACCOUNT_ADDR])
            .with_deploy_hash([3; 32])
            .build();

        ExecuteRequestBuilder::new().push_deploy(deploy).build()
    };
    builder.exec(exec_request_2).expect_success().commit();

    let _account = builder
        .query(None, Key::Account(DEFAULT_ACCOUNT_ADDR), &[])
        .expect("should query account")
        .as_account()
        .cloned()
        .expect("should be account");
}

#[test]
#[ignore]
fn should_not_call_restricted_session_from_wrong_account() {
    // This test runs a contract that's after every call extends the same key with
    // more data
    let exec_request_1 =
        ExecuteRequestBuilder::standard(DEFAULT_ACCOUNT_ADDR, CONTRACT_GROUPS, ()).build();

    let exec_request_2 = ExecuteRequestBuilder::standard(
        DEFAULT_ACCOUNT_ADDR,
        CONTRACT_TRANSFER_TO_ACCOUNT,
        (ACCOUNT_1_ADDR, *TRANSFER_1_AMOUNT),
    )
    .build();

    let mut builder = InMemoryWasmTestBuilder::default();

    builder.run_genesis(&DEFAULT_RUN_GENESIS_REQUEST);

    builder.exec(exec_request_1).expect_success().commit();

    builder.exec(exec_request_2).expect_success().commit();

    let account = builder
        .query(None, Key::Account(DEFAULT_ACCOUNT_ADDR), &[])
        .expect("should query account")
        .as_account()
        .cloned()
        .expect("should be account");

    let metadata_hash = account
        .named_keys()
        .get(METADATA_HASH_KEY)
        .expect("should have contract metadata");
    let _access_uref = account
        .named_keys()
        .get(METADATA_ACCESS_KEY)
        .expect("should have metadata hash");

    let exec_request_3 = {
        let args = runtime_args! {};
        let deploy = DeployItemBuilder::new()
            .with_address(ACCOUNT_1_ADDR)
            .with_stored_versioned_contract_by_hash(
                metadata_hash.into_hash().expect("should be hash"),
                SemVer::V1_0_0,
                RESTRICTED_SESSION,
                args,
            )
            .with_empty_payment_bytes((*DEFAULT_PAYMENT,))
            .with_authorization_keys(&[ACCOUNT_1_ADDR])
            .with_deploy_hash([3; 32])
            .build();

        ExecuteRequestBuilder::new().push_deploy(deploy).build()
    };

    builder.exec(exec_request_3).commit();

    let _account = builder
        .query(None, Key::Account(DEFAULT_ACCOUNT_ADDR), &[])
        .expect("should query account")
        .as_account()
        .cloned()
        .expect("should be account");

    let response = builder
        .get_exec_responses()
        .last()
        .expect("should have last response");
    assert_eq!(response.len(), 1);
    let exec_response = response.last().expect("should have response");
    let error = exec_response.as_error().expect("should have error");
    assert_matches!(error, Error::Exec(execution::Error::InvalidContext));
}

#[test]
#[ignore]
fn should_not_call_restricted_session_caller_from_wrong_account() {
    // This test runs a contract that's after every call extends the same key with
    // more data
    let exec_request_1 =
        ExecuteRequestBuilder::standard(DEFAULT_ACCOUNT_ADDR, CONTRACT_GROUPS, ()).build();

    let exec_request_2 = ExecuteRequestBuilder::standard(
        DEFAULT_ACCOUNT_ADDR,
        CONTRACT_TRANSFER_TO_ACCOUNT,
        (ACCOUNT_1_ADDR, *TRANSFER_1_AMOUNT),
    )
    .build();

    let mut builder = InMemoryWasmTestBuilder::default();

    builder.run_genesis(&DEFAULT_RUN_GENESIS_REQUEST);

    builder.exec(exec_request_1).expect_success().commit();

    builder.exec(exec_request_2).expect_success().commit();

    let account = builder
        .query(None, Key::Account(DEFAULT_ACCOUNT_ADDR), &[])
        .expect("should query account")
        .as_account()
        .cloned()
        .expect("should be account");

    let metadata_hash = account
        .named_keys()
        .get(METADATA_HASH_KEY)
        .expect("should have contract metadata");
    let _access_uref = account
        .named_keys()
        .get(METADATA_ACCESS_KEY)
        .expect("should have metadata hash");

    let exec_request_3 = {
        let args = runtime_args! {
            "metadata_hash"=> metadata_hash.clone(),
        };
        let deploy = DeployItemBuilder::new()
            .with_address(ACCOUNT_1_ADDR)
            .with_stored_versioned_contract_by_hash(
                metadata_hash.into_hash().expect("should be hash"),
                SemVer::V1_0_0,
                RESTRICTED_SESSION_CALLER,
                args,
            )
            .with_empty_payment_bytes((*DEFAULT_PAYMENT,))
            .with_authorization_keys(&[ACCOUNT_1_ADDR])
            .with_deploy_hash([3; 32])
            .build();

        ExecuteRequestBuilder::new().push_deploy(deploy).build()
    };

    builder.exec(exec_request_3).commit();

    let _account = builder
        .query(None, Key::Account(DEFAULT_ACCOUNT_ADDR), &[])
        .expect("should query account")
        .as_account()
        .cloned()
        .expect("should be account");

    let response = builder
        .get_exec_responses()
        .last()
        .expect("should have last response");
    assert_eq!(response.len(), 1);
    let exec_response = response.last().expect("should have response");
    let error = exec_response.as_error().expect("should have error");
    assert_matches!(error, Error::Exec(execution::Error::InvalidContext));
}

#[ignore]
#[test]
fn should_call_group_restricted_contract() {
    // This test runs a contract that's after every call extends the same key with
    // more data
    let exec_request_1 =
        ExecuteRequestBuilder::standard(DEFAULT_ACCOUNT_ADDR, CONTRACT_GROUPS, ()).build();

    let mut builder = InMemoryWasmTestBuilder::default();

    builder.run_genesis(&DEFAULT_RUN_GENESIS_REQUEST);

    builder.exec(exec_request_1).expect_success().commit();

    let account = builder
        .query(None, Key::Account(DEFAULT_ACCOUNT_ADDR), &[])
        .expect("should query account")
        .as_account()
        .cloned()
        .expect("should be account");

    let metadata_hash = account
        .named_keys()
        .get(METADATA_HASH_KEY)
        .expect("should have contract metadata");
    let _access_uref = account
        .named_keys()
        .get(METADATA_ACCESS_KEY)
        .expect("should have metadata hash");

    let exec_request_2 = {
        // This inserts metadata as an argument because this test
        // can work from different accounts which might not have the same keys in their session
        // code.
        let args = runtime_args! {
            METADATA_HASH_ARG => *metadata_hash,
        };
        let deploy = DeployItemBuilder::new()
            .with_address(DEFAULT_ACCOUNT_ADDR)
            .with_stored_versioned_contract_by_name(
                METADATA_HASH_KEY,
                SemVer::V1_0_0,
                RESTRICTED_CONTRACT,
                args,
            )
            .with_empty_payment_bytes((*DEFAULT_PAYMENT,))
            .with_authorization_keys(&[DEFAULT_ACCOUNT_ADDR])
            .with_deploy_hash([3; 32])
            .build();

        ExecuteRequestBuilder::new().push_deploy(deploy).build()
    };

    builder.exec(exec_request_2).expect_success().commit();

    let _account = builder
        .query(None, Key::Account(DEFAULT_ACCOUNT_ADDR), &[])
        .expect("should query account")
        .as_account()
        .cloned()
        .expect("should be account");
}

#[ignore]
#[test]
fn should_call_group_restricted_contract_from_wrong_account() {
    // This test runs a contract that's after every call extends the same key with
    // more data
    let exec_request_1 =
        ExecuteRequestBuilder::standard(DEFAULT_ACCOUNT_ADDR, CONTRACT_GROUPS, ()).build();
    let exec_request_2 = ExecuteRequestBuilder::standard(
        DEFAULT_ACCOUNT_ADDR,
        CONTRACT_TRANSFER_TO_ACCOUNT,
        (ACCOUNT_1_ADDR, *TRANSFER_1_AMOUNT),
    )
    .build();

    let mut builder = InMemoryWasmTestBuilder::default();

    builder.run_genesis(&DEFAULT_RUN_GENESIS_REQUEST);

    builder.exec(exec_request_1).expect_success().commit();
    builder.exec(exec_request_2).expect_success().commit();

    let account = builder
        .query(None, Key::Account(DEFAULT_ACCOUNT_ADDR), &[])
        .expect("should query account")
        .as_account()
        .cloned()
        .expect("should be account");

    let metadata_hash = account
        .named_keys()
        .get(METADATA_HASH_KEY)
        .expect("should have contract metadata");
    let _access_uref = account
        .named_keys()
        .get(METADATA_ACCESS_KEY)
        .expect("should have metadata hash");

    let exec_request_3 = {
        // This inserts metadata as an argument because this test
        // can work from different accounts which might not have the same keys in their session
        // code.
        let args = runtime_args! {
            METADATA_HASH_ARG => *metadata_hash,
        };
        let deploy = DeployItemBuilder::new()
            .with_address(ACCOUNT_1_ADDR)
            .with_stored_versioned_contract_by_hash(
                metadata_hash.into_hash().expect("should be hash"),
                SemVer::V1_0_0,
                RESTRICTED_CONTRACT,
                args,
            )
            .with_empty_payment_bytes((*DEFAULT_PAYMENT,))
            .with_authorization_keys(&[ACCOUNT_1_ADDR])
            .with_deploy_hash([3; 32])
            .build();

        ExecuteRequestBuilder::new().push_deploy(deploy).build()
    };

    builder.exec(exec_request_3).commit();

    let response = builder
        .get_exec_responses()
        .last()
        .expect("should have last response");
    assert_eq!(response.len(), 1);
    let exec_response = response.last().expect("should have response");
    let error = exec_response.as_error().expect("should have error");
    assert_matches!(error, Error::Exec(execution::Error::InvalidContext));
}

#[ignore]
#[test]
fn should_call_group_unrestricted_contract_caller() {
    // This test runs a contract that's after every call extends the same key with
    // more data
    let exec_request_1 =
        ExecuteRequestBuilder::standard(DEFAULT_ACCOUNT_ADDR, CONTRACT_GROUPS, ()).build();

    let mut builder = InMemoryWasmTestBuilder::default();

    builder.run_genesis(&DEFAULT_RUN_GENESIS_REQUEST);

    builder.exec(exec_request_1).expect_success().commit();

    let account = builder
        .query(None, Key::Account(DEFAULT_ACCOUNT_ADDR), &[])
        .expect("should query account")
        .as_account()
        .cloned()
        .expect("should be account");

    let metadata_hash = account
        .named_keys()
        .get(METADATA_HASH_KEY)
        .expect("should have contract metadata");
    let _access_uref = account
        .named_keys()
        .get(METADATA_ACCESS_KEY)
        .expect("should have metadata hash");

    let exec_request_2 = {
        let args = runtime_args! {
            METADATA_HASH_ARG => *metadata_hash,
        };
        let deploy = DeployItemBuilder::new()
            .with_address(DEFAULT_ACCOUNT_ADDR)
            .with_stored_versioned_contract_by_name(
                METADATA_HASH_KEY,
                SemVer::V1_0_0,
                UNRESTRICTED_CONTRACT_CALLER,
                args,
            )
            .with_empty_payment_bytes((*DEFAULT_PAYMENT,))
            .with_authorization_keys(&[DEFAULT_ACCOUNT_ADDR])
            .with_deploy_hash([3; 32])
            .build();

        ExecuteRequestBuilder::new().push_deploy(deploy).build()
    };
    builder.exec(exec_request_2).expect_success().commit();

    let _account = builder
        .query(None, Key::Account(DEFAULT_ACCOUNT_ADDR), &[])
        .expect("should query account")
        .as_account()
        .cloned()
        .expect("should be account");
}

#[ignore]
#[test]
fn should_call_unrestricted_contract_caller_from_different_account() {
    let exec_request_1 =
        ExecuteRequestBuilder::standard(DEFAULT_ACCOUNT_ADDR, CONTRACT_GROUPS, ()).build();
    let exec_request_2 = ExecuteRequestBuilder::standard(
        DEFAULT_ACCOUNT_ADDR,
        CONTRACT_TRANSFER_TO_ACCOUNT,
        (ACCOUNT_1_ADDR, *TRANSFER_1_AMOUNT),
    )
    .build();

    let mut builder = InMemoryWasmTestBuilder::default();

    builder.run_genesis(&DEFAULT_RUN_GENESIS_REQUEST);

    builder.exec(exec_request_1).expect_success().commit();
    builder.exec(exec_request_2).expect_success().commit();

    let account = builder
        .query(None, Key::Account(DEFAULT_ACCOUNT_ADDR), &[])
        .expect("should query account")
        .as_account()
        .cloned()
        .expect("should be account");

    let metadata_hash = account
        .named_keys()
        .get(METADATA_HASH_KEY)
        .expect("should have contract metadata");
    let _access_uref = account
        .named_keys()
        .get(METADATA_ACCESS_KEY)
        .expect("should have metadata hash");

    let exec_request_3 = {
        // This inserts metadata as an argument because this test
        // can work from different accounts which might not have the same keys in their session
        // code.
        let args = runtime_args! {
            METADATA_HASH_ARG => *metadata_hash,
        };
        let deploy = DeployItemBuilder::new()
            .with_address(ACCOUNT_1_ADDR)
            .with_stored_versioned_contract_by_hash(
                metadata_hash.into_hash().expect("should be hash"),
                SemVer::V1_0_0,
                UNRESTRICTED_CONTRACT_CALLER,
                args,
            )
            .with_empty_payment_bytes((*DEFAULT_PAYMENT,))
            .with_authorization_keys(&[ACCOUNT_1_ADDR])
            .with_deploy_hash([3; 32])
            .build();

        ExecuteRequestBuilder::new().push_deploy(deploy).build()
    };

    builder.exec(exec_request_3).expect_success().commit();
}

#[ignore]
#[test]
fn should_call_group_restricted_contract_as_session() {
    // This test runs a contract that's after every call extends the same key with
    // more data
    let exec_request_1 =
        ExecuteRequestBuilder::standard(DEFAULT_ACCOUNT_ADDR, CONTRACT_GROUPS, ()).build();
    let exec_request_2 = ExecuteRequestBuilder::standard(
        DEFAULT_ACCOUNT_ADDR,
        CONTRACT_TRANSFER_TO_ACCOUNT,
        (ACCOUNT_1_ADDR, *TRANSFER_1_AMOUNT),
    )
    .build();

    let mut builder = InMemoryWasmTestBuilder::default();

    builder.run_genesis(&DEFAULT_RUN_GENESIS_REQUEST);

    builder.exec(exec_request_1).expect_success().commit();
    builder.exec(exec_request_2).expect_success().commit();

    let account = builder
        .query(None, Key::Account(DEFAULT_ACCOUNT_ADDR), &[])
        .expect("should query account")
        .as_account()
        .cloned()
        .expect("should be account");

    let metadata_hash = account
        .named_keys()
        .get(METADATA_HASH_KEY)
        .expect("should have contract metadata");
    let _access_uref = account
        .named_keys()
        .get(METADATA_ACCESS_KEY)
        .expect("should have metadata hash");

    let exec_request_3 = {
        // This inserts metadata as an argument because this test
        // can work from different accounts which might not have the same keys in their session
        // code.
        let args = runtime_args! {
            METADATA_HASH_ARG => *metadata_hash,
        };
        let deploy = DeployItemBuilder::new()
            .with_address(DEFAULT_ACCOUNT_ADDR)
            .with_stored_versioned_contract_by_hash(
                metadata_hash.into_hash().expect("should be hash"),
                SemVer::V1_0_0,
                RESTRICTED_CONTRACT_CALLER_AS_SESSION,
                args,
            )
            .with_empty_payment_bytes((*DEFAULT_PAYMENT,))
            .with_authorization_keys(&[DEFAULT_ACCOUNT_ADDR])
            .with_deploy_hash([4; 32])
            .build();

        ExecuteRequestBuilder::new().push_deploy(deploy).build()
    };

    builder.exec(exec_request_3).expect_success().commit();
}

#[ignore]
#[test]
fn should_call_group_restricted_contract_as_session_from_wrong_account() {
    // This test runs a contract that's after every call extends the same key with
    // more data
    let exec_request_1 =
        ExecuteRequestBuilder::standard(DEFAULT_ACCOUNT_ADDR, CONTRACT_GROUPS, ()).build();
    let exec_request_2 = ExecuteRequestBuilder::standard(
        DEFAULT_ACCOUNT_ADDR,
        CONTRACT_TRANSFER_TO_ACCOUNT,
        (ACCOUNT_1_ADDR, *TRANSFER_1_AMOUNT),
    )
    .build();

    let mut builder = InMemoryWasmTestBuilder::default();

    builder.run_genesis(&DEFAULT_RUN_GENESIS_REQUEST);

    builder.exec(exec_request_1).expect_success().commit();
    builder.exec(exec_request_2).expect_success().commit();

    let account = builder
        .query(None, Key::Account(DEFAULT_ACCOUNT_ADDR), &[])
        .expect("should query account")
        .as_account()
        .cloned()
        .expect("should be account");

    let metadata_hash = account
        .named_keys()
        .get(METADATA_HASH_KEY)
        .expect("should have contract metadata");
    let _access_uref = account
        .named_keys()
        .get(METADATA_ACCESS_KEY)
        .expect("should have metadata hash");

    let exec_request_3 = {
        // This inserts metadata as an argument because this test
        // can work from different accounts which might not have the same keys in their session
        // code.
        let args = runtime_args! {
            METADATA_HASH_ARG => *metadata_hash,
        };
        let deploy = DeployItemBuilder::new()
            .with_address(ACCOUNT_1_ADDR)
            .with_stored_versioned_contract_by_hash(
                metadata_hash.into_hash().expect("should be hash"),
                SemVer::V1_0_0,
                RESTRICTED_CONTRACT_CALLER_AS_SESSION,
                args,
            )
            .with_empty_payment_bytes((*DEFAULT_PAYMENT,))
            .with_authorization_keys(&[ACCOUNT_1_ADDR])
            .with_deploy_hash([4; 32])
            .build();

        ExecuteRequestBuilder::new().push_deploy(deploy).build()
    };

    builder.exec(exec_request_3).commit();

    let response = builder
        .get_exec_responses()
        .last()
        .expect("should have last response");
    assert_eq!(response.len(), 1);
    let exec_response = response.last().expect("should have response");
    let error = exec_response.as_error().expect("should have error");
    assert_matches!(error, Error::Exec(execution::Error::InvalidContext));
}
