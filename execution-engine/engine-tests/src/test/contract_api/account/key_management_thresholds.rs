use engine_test_support::low_level::{
    DeployItemBuilder, ExecuteRequestBuilder, InMemoryWasmTestBuilder, DEFAULT_ACCOUNT_ADDR,
    DEFAULT_GENESIS_CONFIG, DEFAULT_PAYMENT, STANDARD_PAYMENT_CONTRACT,
};
use types::account::PublicKey;

const CONTRACT_KEY_MANAGEMENT_THRESHOLDS: &str = "key_management_thresholds.wasm";

#[ignore]
#[test]
fn should_verify_key_management_permission_with_low_weight() {
    let exec_request_1 = ExecuteRequestBuilder::standard(
        DEFAULT_ACCOUNT_ADDR,
        CONTRACT_KEY_MANAGEMENT_THRESHOLDS,
        (String::from("init"),),
    )
    .build();
    let exec_request_2 = ExecuteRequestBuilder::standard(
        DEFAULT_ACCOUNT_ADDR,
        CONTRACT_KEY_MANAGEMENT_THRESHOLDS,
        (String::from("test-permission-denied"),),
    )
    .build();
    InMemoryWasmTestBuilder::default()
        .run_genesis(&DEFAULT_GENESIS_CONFIG)
        .exec(exec_request_1)
        .expect_success()
        .commit()
        .exec(exec_request_2)
        .expect_success()
        .commit();
}

#[ignore]
#[test]
fn should_verify_key_management_permission_with_sufficient_weight() {
    let exec_request_1 = ExecuteRequestBuilder::standard(
        DEFAULT_ACCOUNT_ADDR,
        CONTRACT_KEY_MANAGEMENT_THRESHOLDS,
        (String::from("init"),),
    )
    .build();
    let exec_request_2 = {
        let deploy = DeployItemBuilder::new()
            .with_address(DEFAULT_ACCOUNT_ADDR)
            .with_payment_code(STANDARD_PAYMENT_CONTRACT, (*DEFAULT_PAYMENT,))
            // This test verifies that all key management operations succeed
            .with_session_code(
                "key_management_thresholds.wasm",
                (String::from("test-key-mgmnt-succeed"),),
            )
            .with_deploy_hash([2u8; 32])
            .with_authorization_keys(&[
                PublicKey::new(DEFAULT_ACCOUNT_ADDR),
                // Key [42; 32] is created in init stage
                PublicKey::new([42; 32]),
            ])
            .build();
        ExecuteRequestBuilder::from_deploy_item(deploy).build()
    };
    InMemoryWasmTestBuilder::default()
        .run_genesis(&DEFAULT_GENESIS_CONFIG)
        .exec(exec_request_1)
        .expect_success()
        .commit()
        .exec(exec_request_2)
        .expect_success()
        .commit();
}
