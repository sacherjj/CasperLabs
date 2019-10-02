use contract_ffi::value::account::PublicKey;
use contract_ffi::value::U512;
use engine_core::engine_state::MAX_PAYMENT;

use crate::support::test_support::{
    DeployItemBuilder, ExecuteRequestBuilder, InMemoryWasmTestBuilder, STANDARD_PAYMENT_CONTRACT,
};
use crate::test::{DEFAULT_ACCOUNT_ADDR, DEFAULT_GENESIS_CONFIG};

const CONTRACT_KEY_MANAGEMENT_THRESHOLDS: &str = "key_management_thresholds";

#[ignore]
#[test]
fn should_verify_key_management_permission_with_low_weight() {
    let exec_request_1 = {
        let contract_name = format!("{}.wasm", CONTRACT_KEY_MANAGEMENT_THRESHOLDS);
        ExecuteRequestBuilder::standard(
            DEFAULT_ACCOUNT_ADDR,
            &contract_name,
            (String::from("init"),),
        )
    };
    let exec_request_2 = {
        let contract_name = format!("{}.wasm", CONTRACT_KEY_MANAGEMENT_THRESHOLDS);
        ExecuteRequestBuilder::standard(
            DEFAULT_ACCOUNT_ADDR,
            &contract_name,
            (String::from("test-permission-denied"),),
        )
    };
    InMemoryWasmTestBuilder::default()
        .run_genesis(&DEFAULT_GENESIS_CONFIG)
        .exec_with_exec_request(exec_request_1)
        .expect_success()
        .commit()
        .exec_with_exec_request(exec_request_2)
        .expect_success()
        .commit();
}

#[ignore]
#[test]
fn should_verify_key_management_permission_with_sufficient_weight() {
    let exec_request_1 = {
        let contract_name = format!("{}.wasm", CONTRACT_KEY_MANAGEMENT_THRESHOLDS);
        ExecuteRequestBuilder::standard(
            DEFAULT_ACCOUNT_ADDR,
            &contract_name,
            (String::from("init"),),
        )
    };
    let exec_request_2 = {
        let deploy = DeployItemBuilder::new()
            .with_address(DEFAULT_ACCOUNT_ADDR)
            .with_payment_code(STANDARD_PAYMENT_CONTRACT, (U512::from(MAX_PAYMENT),))
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
        .exec_with_exec_request(exec_request_1)
        .expect_success()
        .commit()
        .exec_with_exec_request(exec_request_2)
        .expect_success()
        .commit();
}
