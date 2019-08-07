extern crate casperlabs_engine_grpc_server;
extern crate contract_ffi;
extern crate engine_core;
extern crate engine_shared;
extern crate engine_storage;
extern crate grpc;

#[allow(dead_code)]
mod test_support;

use std::collections::HashMap;

use contract_ffi::value::account::{PublicKey, Weight};
use engine_core::engine_state::error;
use engine_core::execution;
use test_support::{WasmTestBuilder, DEFAULT_BLOCK_TIME};

const GENESIS_ADDR: [u8; 32] = [7u8; 32];

#[ignore]
#[test]
fn should_deploy_with_authorized_identity_key() {
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
fn should_raise_auth_failure_with_invalid_key() {
    // tests that authorized keys that does not belong to account raises AuthorizationError
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

    assert_eq!(message, format!("{}", error::Error::AuthorizationError))
}

#[ignore]
#[test]
fn should_raise_auth_failure_with_invalid_keys() {
    // tests that authorized keys that does not belong to account raises AuthorizationError
    let key_1 = [254; 32];
    let key_2 = [253; 32];
    let key_3 = [252; 32];
    assert_ne!(GENESIS_ADDR, key_1);
    assert_ne!(GENESIS_ADDR, key_2);
    assert_ne!(GENESIS_ADDR, key_3);
    // Basic deploy with single key
    let result = WasmTestBuilder::default()
        .run_genesis(GENESIS_ADDR, HashMap::new())
        .exec_with_args_and_keys(
            GENESIS_ADDR,
            "authorized_keys.wasm",
            1,                                // blocktime
            1,                                // nonce
            (Weight::new(1), Weight::new(1)), //args
            vec![
                PublicKey::new(key_2),
                PublicKey::new(key_1),
                PublicKey::new(key_3),
            ],
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

    assert_eq!(message, format!("{}", error::Error::AuthorizationError))
}

#[ignore]
#[test]
fn should_raise_deploy_authorization_failure() {
    // tests that authorized keys needs sufficient cumulative weight
    let key_1 = [254; 32];
    let key_2 = [253; 32];
    let key_3 = [252; 32];
    assert_ne!(GENESIS_ADDR, key_1);
    assert_ne!(GENESIS_ADDR, key_2);
    assert_ne!(GENESIS_ADDR, key_3);
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
        .exec_with_args(
            GENESIS_ADDR,
            "add_update_associated_key.wasm",
            DEFAULT_BLOCK_TIME,
            2,
            PublicKey::new(key_2),
        )
        .expect_success()
        .commit()
        .exec_with_args(
            GENESIS_ADDR,
            "add_update_associated_key.wasm",
            DEFAULT_BLOCK_TIME,
            3,
            PublicKey::new(key_3),
        )
        .expect_success()
        .commit()
        // This should execute successfuly - change deploy and key management
        // thresholds.
        .exec_with_args_and_keys(
            GENESIS_ADDR,
            "authorized_keys.wasm",
            DEFAULT_BLOCK_TIME,
            4, // nonce
            // Deploy threshold is equal to 3, keymgmnt is still 1.
            // Even after verifying weights and thresholds to not
            // lock out the account, those values should work as
            // account now has 1. identity key with weight=1 and
            // a key with weight=2.
            (Weight::new(4), Weight::new(3)), //args
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
            5, // nonce
            // Next deploy will see deploy threshold == 4, keymgmnt == 5
            (Weight::new(5), Weight::new(4)), //args
            vec![PublicKey::new(key_1)],
        )
        .commit()
        .finish();

    {
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
        );
    }

    // identity key (w: 1) and key_1 (w: 2) passes threshold of 3
    let result3 = WasmTestBuilder::from_result(result2)
        .exec_with_args_and_keys(
            GENESIS_ADDR,
            "authorized_keys.wasm",
            DEFAULT_BLOCK_TIME,
            5, // nonce
            // change deployment threshold to 4
            (Weight::new(6), Weight::new(5)), //args
            vec![
                PublicKey::new(GENESIS_ADDR),
                PublicKey::new(key_1),
                PublicKey::new(key_2),
                PublicKey::new(key_3),
            ],
        )
        .expect_success()
        .commit()
        .finish();

    // deployment threshold is now 4
    // failure: key_2 weight + key_1 weight < deployment threshold
    let result4 = WasmTestBuilder::from_result(result3)
        .exec_with_args_and_keys(
            GENESIS_ADDR,
            "authorized_keys.wasm",
            DEFAULT_BLOCK_TIME,
            6,                                // nonce
            (Weight::new(0), Weight::new(0)), //args
            vec![PublicKey::new(key_2), PublicKey::new(key_1)],
        )
        .commit()
        .finish();

    {
        let deploy_result = result4
            .builder()
            .get_exec_response(0)
            .expect("should have exec response")
            .get_success()
            .get_deploy_results()
            .get(0)
            .expect("should have at least one deploy result");

        assert!(deploy_result.has_execution_result(), "{:?}", deploy_result);
        let execution_result = deploy_result.get_execution_result();
        assert!(execution_result.has_error());
        let error = execution_result.get_error();
        assert!(error.has_exec_error());
        let exec_error = error.get_exec_error();
        assert_eq!(
            exec_error.get_message(),
            format!("{}", execution::Error::DeploymentAuthorizationFailure)
        );
    }

    // success: identity key weight + key_1 weight + key_2 weight >= deployment threshold
    WasmTestBuilder::from_result(result4)
        .exec_with_args_and_keys(
            GENESIS_ADDR,
            "authorized_keys.wasm",
            DEFAULT_BLOCK_TIME,
            6,                                // nonce
            (Weight::new(0), Weight::new(0)), //args
            vec![
                PublicKey::new(GENESIS_ADDR),
                PublicKey::new(key_1),
                PublicKey::new(key_2),
                PublicKey::new(key_3),
            ],
        )
        .commit()
        .expect_success()
        .finish();
}

#[ignore]
#[test]
fn should_authorize_deploy_with_multiple_keys() {
    // tests that authorized keys needs sufficient cumulative weight
    // and each of the associated keys is greater than threshold

    let key_1 = [254; 32];
    let key_2 = [253; 32];
    assert_ne!(GENESIS_ADDR, key_1);
    assert_ne!(GENESIS_ADDR, key_2);
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
        .exec_with_args(
            GENESIS_ADDR,
            "add_update_associated_key.wasm",
            DEFAULT_BLOCK_TIME,
            2,
            PublicKey::new(key_2),
        )
        .expect_success()
        .commit()
        .finish();

    // key_1 (w: 2) key_2 (w: 2) each passes default threshold of 1
    WasmTestBuilder::from_result(result1)
        .exec_with_args_and_keys(
            GENESIS_ADDR,
            "authorized_keys.wasm",
            DEFAULT_BLOCK_TIME,
            3, // nonce
            // change deployment threshold to 4
            (Weight::new(0), Weight::new(0)), //args
            vec![PublicKey::new(key_2), PublicKey::new(key_1)],
        )
        .expect_success()
        .commit();
}

#[ignore]
#[test]
fn should_not_authorize_deploy_with_duplicated_keys() {
    // tests that authorized keys needs sufficient cumulative weight
    // and each of the associated keys is greater than threshold
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
        .exec_with_args_and_keys(
            GENESIS_ADDR,
            "authorized_keys.wasm",
            DEFAULT_BLOCK_TIME,
            2, // nonce
            // change deployment threshold to 3
            (Weight::new(4), Weight::new(3)), //args
            vec![PublicKey::new(GENESIS_ADDR)],
        )
        .expect_success()
        .commit()
        .finish();

    // success: identity key weight + key_1 weight + key_2 weight >= deployment threshold
    let final_result = WasmTestBuilder::from_result(result1)
        .exec_with_args_and_keys(
            GENESIS_ADDR,
            "authorized_keys.wasm",
            DEFAULT_BLOCK_TIME,
            3,                                // nonce
            (Weight::new(0), Weight::new(0)), //args
            vec![
                PublicKey::new(key_1),
                PublicKey::new(key_1),
                PublicKey::new(key_1),
                PublicKey::new(key_1),
                PublicKey::new(key_1),
                PublicKey::new(key_1),
                PublicKey::new(key_1),
                PublicKey::new(key_1),
                PublicKey::new(key_1),
                PublicKey::new(key_1),
            ],
        )
        .commit()
        .finish();
    let deploy_result = final_result
        .builder()
        .get_exec_response(0)
        .expect("should have exec response")
        .get_success()
        .get_deploy_results()
        .get(0)
        .expect("should have at least one deploy result");

    assert!(deploy_result.has_execution_result(), "{:?}", deploy_result);
    let execution_result = deploy_result.get_execution_result();
    assert!(execution_result.has_error());
    let error = execution_result.get_error();
    assert!(error.has_exec_error());
    let exec_error = error.get_exec_error();
    assert_eq!(
        exec_error.get_message(),
        format!("{}", execution::Error::DeploymentAuthorizationFailure)
    );
}
