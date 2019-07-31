extern crate casperlabs_engine_grpc_server;
extern crate common;
extern crate execution_engine;
extern crate grpc;
extern crate shared;
extern crate storage;

#[allow(dead_code)]
mod test_support;

use std::collections::HashMap;

use common::value::account::{PublicKey, Weight};
use execution_engine::engine_state::error;
use execution_engine::execution;
use test_support::{WasmTestBuilder, DEFAULT_BLOCK_TIME};

const GENESIS_ADDR: [u8; 32] = [7u8; 32];

#[ignore]
#[test]
fn should_deploy_with_authorized_keys() {
    // Basic deploy with single key
    WasmTestBuilder::default()
        .run_genesis(GENESIS_ADDR, HashMap::new())
        .exec_with_args_and_keys(
            GENESIS_ADDR,
            "authorized_keys.wasm",
            1,                                // blocktime
            1,                                // nonce
            (Weight::new(1), Weight::new(1)), //args
            vec![PublicKey::new(GENESIS_ADDR)],
        )
        .commit()
        .expect_success();
}

#[ignore]
#[test]
fn should_raise_auth_failure_with_invalid_keys() {
    // tests that authorized keys that does not belong to account raises AuthorizationFailure
    let key_1 = [254; 32];
    assert_ne!(GENESIS_ADDR, key_1);
    // Basic deploy with single key
    let result = WasmTestBuilder::default()
        .run_genesis(GENESIS_ADDR, HashMap::new())
        .exec_with_args_and_keys(
            GENESIS_ADDR,
            "authorized_keys.wasm",
            1,                                // blocktime
            1,                                // nonce
            (Weight::new(1), Weight::new(1)), //args
            vec![PublicKey::new(key_1)],
        )
        .commit()
        .finish();

    let deploy_result = result
        .builder()
        .get_exec_response(0)
        .expect("should have exec response")
        .get_success()
        .get_deploy_results()
        .get(0)
        .expect("should have at least one deploy result");

    assert!(deploy_result.has_precondition_failure());
    let message = deploy_result.get_precondition_failure().get_message();

    assert_eq!(message, format!("{}", error::Error::AuthorizationFailure))
}

#[ignore]
#[test]
fn should_raise_deploy_with_insufficient_weight() {
    // tests that authorized keys needs sufficient cumulative weight
    let key_1 = [254; 32];
    assert_ne!(GENESIS_ADDR, key_1);
    // Basic deploy with single key
    let result1 = WasmTestBuilder::default()
        .run_genesis(GENESIS_ADDR, HashMap::new())
        // Reusing a test contract that would add new key
        .exec_with_args(
            GENESIS_ADDR,
            "add_update_associated_key.wasm",
            DEFAULT_BLOCK_TIME,
            1,
            PublicKey::new(key_1),
        )
        .expect_success()
        .commit()
        // This should execute successfuly - change deploy and key management
        // thresholds.
        .exec_with_args_and_keys(
            GENESIS_ADDR,
            "authorized_keys.wasm",
            DEFAULT_BLOCK_TIME,
            2, // nonce
            // Deploy threshold is equal to 3, keymgmnt is still 1.
            // Even after verifying weights and thresholds to not
            // lock out the account, those values should work as
            // account now has 1. identity key with weight=1 and
            // a key with weight=2.
            (Weight::new(3), Weight::new(4)), //args
            vec![PublicKey::new(GENESIS_ADDR)],
        )
        .expect_success()
        .commit()
        .finish();

    // With deploy threshold == 3 using single secondary key
    // with weight == 2 should raise deploy authorization failure.
    let result2 = WasmTestBuilder::from_result(result1)
        .exec_with_args_and_keys(
            GENESIS_ADDR,
            "authorized_keys.wasm",
            DEFAULT_BLOCK_TIME,
            3, // nonce
            // Deploy threshold is equal to 3, keymgmnt is still 1
            (Weight::new(3), Weight::new(4)), //args
            vec![PublicKey::new(key_1)],
        )
        .commit()
        .finish();

    let deploy_result = result2
        .builder()
        .get_exec_response(0)
        .expect("should have exec response")
        .get_success()
        .get_deploy_results()
        .get(0)
        .expect("should have at least one deploy result");

    assert!(deploy_result.has_execution_result());
    let execution_result = deploy_result.get_execution_result();
    assert!(execution_result.has_error());
    let error = execution_result.get_error();
    assert!(error.has_exec_error());
    let exec_error = error.get_exec_error();

    assert_eq!(
        exec_error.get_message(),
        format!("{}", execution::Error::DeploymentAuthorizationFailure)
    )
}
