use contract_ffi::value::account::PublicKey;
use contract_ffi::value::U512;
use engine_core::engine_state::MAX_PAYMENT;

use crate::support::test_support::{
    InMemoryWasmTestBuilder, DEFAULT_BLOCK_TIME, STANDARD_PAYMENT_CONTRACT,
};
use crate::test::{DEFAULT_ACCOUNT_ADDR, DEFAULT_GENESIS_CONFIG};

#[ignore]
#[test]
fn should_verify_key_management_permission_with_low_weight() {
    InMemoryWasmTestBuilder::default()
        .run_genesis(&DEFAULT_GENESIS_CONFIG)
        .exec_with_args(
            DEFAULT_ACCOUNT_ADDR,
            STANDARD_PAYMENT_CONTRACT,
            (U512::from(MAX_PAYMENT),),
            "key_management_thresholds.wasm",
            (String::from("init"),),
            DEFAULT_BLOCK_TIME,
            [1u8; 32],
        )
        .expect_success()
        .commit()
        .exec_with_args(
            DEFAULT_ACCOUNT_ADDR,
            STANDARD_PAYMENT_CONTRACT,
            (U512::from(MAX_PAYMENT),),
            "key_management_thresholds.wasm",
            // This test verifies that any other error than PermissionDenied would revert
            (String::from("test-permission-denied"),),
            DEFAULT_BLOCK_TIME,
            [2u8; 32],
        )
        .expect_success()
        .commit();
}

#[ignore]
#[test]
fn should_verify_key_management_permission_with_sufficient_weight() {
    InMemoryWasmTestBuilder::default()
        .run_genesis(&DEFAULT_GENESIS_CONFIG)
        .exec_with_args(
            DEFAULT_ACCOUNT_ADDR,
            STANDARD_PAYMENT_CONTRACT,
            (U512::from(MAX_PAYMENT),),
            "key_management_thresholds.wasm",
            (String::from("init"),),
            DEFAULT_BLOCK_TIME,
            [1u8; 32],
        )
        .expect_success()
        .commit()
        .exec_with_args_and_keys(
            DEFAULT_ACCOUNT_ADDR,
            STANDARD_PAYMENT_CONTRACT,
            (U512::from(MAX_PAYMENT),),
            "key_management_thresholds.wasm",
            // This test verifies that all key management operations succeed
            (String::from("test-key-mgmnt-succeed"),),
            DEFAULT_BLOCK_TIME,
            [2u8; 32],
            vec![
                PublicKey::new(DEFAULT_ACCOUNT_ADDR),
                // Key [42; 32] is created in init stage
                PublicKey::new([42; 32]),
            ],
        )
        .expect_success()
        .commit();
}
