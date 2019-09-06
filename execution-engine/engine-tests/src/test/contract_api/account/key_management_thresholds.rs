use std::collections::HashMap;

use contract_ffi::value::account::PublicKey;

use crate::support::test_support::{
    WasmTestBuilder, DEFAULT_BLOCK_TIME, STANDARD_PAYMENT_CONTRACT,
};
use contract_ffi::value::U512;
use engine_core::engine_state::MAX_PAYMENT;

const GENESIS_ADDR: [u8; 32] = [6u8; 32];

#[ignore]
#[test]
fn should_verify_key_management_permission_with_low_weight() {
    WasmTestBuilder::default()
        .run_genesis(GENESIS_ADDR, HashMap::new())
        .exec_with_args(
            GENESIS_ADDR,
            STANDARD_PAYMENT_CONTRACT,
            (U512::from(MAX_PAYMENT),),
            "key_management_thresholds.wasm",
            (String::from("init"),),
            DEFAULT_BLOCK_TIME,
            1,
        )
        .expect_success()
        .commit()
        .exec_with_args(
            GENESIS_ADDR,
            STANDARD_PAYMENT_CONTRACT,
            (U512::from(MAX_PAYMENT),),
            "key_management_thresholds.wasm",
            // This test verifies that any other error than PermissionDenied would revert
            (String::from("test-permission-denied"),),
            DEFAULT_BLOCK_TIME,
            2,
        )
        .expect_success()
        .commit();
}

#[ignore]
#[test]
fn should_verify_key_management_permission_with_sufficient_weight() {
    WasmTestBuilder::default()
        .run_genesis(GENESIS_ADDR, HashMap::new())
        .exec_with_args(
            GENESIS_ADDR,
            STANDARD_PAYMENT_CONTRACT,
            (U512::from(MAX_PAYMENT),),
            "key_management_thresholds.wasm",
            (String::from("init"),),
            DEFAULT_BLOCK_TIME,
            1,
        )
        .expect_success()
        .commit()
        .exec_with_args_and_keys(
            GENESIS_ADDR,
            STANDARD_PAYMENT_CONTRACT,
            (U512::from(MAX_PAYMENT),),
            "key_management_thresholds.wasm",
            // This test verifies that all key management operations succeed
            (String::from("test-key-mgmnt-succeed"),),
            DEFAULT_BLOCK_TIME,
            2,
            vec![
                PublicKey::new(GENESIS_ADDR),
                // Key [42; 32] is created in init stage
                PublicKey::new([42; 32]),
            ],
        )
        .expect_success()
        .commit();
}
