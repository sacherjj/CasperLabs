use engine_test_support::{
    internal::{
        DeployItemBuilder, ExecuteRequestBuilder, InMemoryWasmTestBuilder, DEFAULT_PAYMENT,
        DEFAULT_RUN_GENESIS_REQUEST,
    },
    DEFAULT_ACCOUNT_ADDR,
};
use types::{runtime_args, Group, Key, RuntimeArgs, SemVer};

const CONTRACT_GROUPS: &str = "manage_groups.wasm";
const METADATA_HASH_KEY: &str = "metadata_hash_key";
const METADATA_ACCESS_KEY: &str = "metadata_access_key";
const CREATE_GROUPS: &str = "create_groups";
const REMOVE_GROUP: &str = "remove_group";
const GROUP_NAME_ARG: &str = "group_name";

#[ignore]
#[test]
fn should_create_and_remove_group() {
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
        let args = runtime_args! {};
        let deploy = DeployItemBuilder::new()
            .with_address(DEFAULT_ACCOUNT_ADDR)
            .with_stored_versioned_contract_by_name(
                METADATA_HASH_KEY,
                SemVer::V1_0_0,
                CREATE_GROUPS,
                args,
            )
            .with_empty_payment_bytes((*DEFAULT_PAYMENT,))
            .with_authorization_keys(&[DEFAULT_ACCOUNT_ADDR])
            .with_deploy_hash([3; 32])
            .build();

        ExecuteRequestBuilder::new().push_deploy(deploy).build()
    };

    builder.exec(exec_request_2).expect_success().commit();

    let query_result = builder
        .query(None, *metadata_hash, &[])
        .expect("should have result");
    let contract_metadata = query_result
        .as_contract_metadata()
        .expect("should be metadata");
    assert_eq!(contract_metadata.groups().len(), 1);
    let group_1 = contract_metadata
        .groups()
        .get(&Group::new("Group 1"))
        .expect("should have group");
    assert_eq!(group_1.len(), 2);

    let exec_request_3 = {
        // This inserts metadata as an argument because this test
        // can work from different accounts which might not have the same keys in their session
        // code.
        let args = runtime_args! {
            GROUP_NAME_ARG => "Group 1",
        };
        let deploy = DeployItemBuilder::new()
            .with_address(DEFAULT_ACCOUNT_ADDR)
            .with_stored_versioned_contract_by_name(
                METADATA_HASH_KEY,
                SemVer::V1_0_0,
                REMOVE_GROUP,
                args,
            )
            .with_empty_payment_bytes((*DEFAULT_PAYMENT,))
            .with_authorization_keys(&[DEFAULT_ACCOUNT_ADDR])
            .with_deploy_hash([3; 32])
            .build();

        ExecuteRequestBuilder::new().push_deploy(deploy).build()
    };

    builder.exec(exec_request_3).expect_success().commit();

    let query_result = builder
        .query(None, *metadata_hash, &[])
        .expect("should have result");
    let contract_metadata = query_result
        .as_contract_metadata()
        .expect("should be metadata");
    assert_eq!(contract_metadata.groups().get(&Group::new("Group 1")), None);
}
