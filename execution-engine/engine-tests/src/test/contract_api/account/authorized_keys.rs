use std::collections::HashMap;

use crate::support::test_support::{
    InMemoryWasmTestBuilder, DEFAULT_BLOCK_TIME, STANDARD_PAYMENT_CONTRACT,
};
use contract_ffi::value::account::{PublicKey, Weight};
use contract_ffi::value::U512;
use engine_core::engine_state::{self, MAX_PAYMENT};
use engine_core::execution;

const GENESIS_ADDR: [u8; 32] = [7u8; 32];

#[ignore]
#[test]
fn should_deploy_with_authorized_identity_key() {
    // Basic deploy with single key
    InMemoryWasmTestBuilder::default()
        .run_genesis(GENESIS_ADDR, HashMap::new())
        .exec_with_args_and_keys(
            GENESIS_ADDR,
            STANDARD_PAYMENT_CONTRACT,
            (U512::from(MAX_PAYMENT),),
            "authorized_keys.wasm",
            (Weight::new(1), Weight::new(1)), //args
            1,                                // blocktime
            [1u8; 32],                        //deploy hash
            vec![PublicKey::new(GENESIS_ADDR)],
        )
        .commit()
        .expect_success();
}

#[ignore]
#[test]
fn should_raise_auth_failure_with_invalid_key() {
    // tests that authorized keys that does not belong to account raises
    // AuthorizationError
    let key_1 = [254; 32];
    assert_ne!(GENESIS_ADDR, key_1);
    // Basic deploy with single key
    let result = InMemoryWasmTestBuilder::default()
        .run_genesis(GENESIS_ADDR, HashMap::new())
        .exec_with_args_and_keys(
            GENESIS_ADDR,
            STANDARD_PAYMENT_CONTRACT,
            (U512::from(MAX_PAYMENT),),
            "authorized_keys.wasm",
            (Weight::new(1), Weight::new(1)), //args
            1,                                // blocktime
            [1u8; 32],                        //deploy hash
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

    assert_eq!(
        message,
        format!("{}", engine_state::error::Error::AuthorizationError)
    )
}

#[ignore]
#[test]
fn should_raise_auth_failure_with_invalid_keys() {
    // tests that authorized keys that does not belong to account raises
    // AuthorizationError
    let key_1 = [254; 32];
    let key_2 = [253; 32];
    let key_3 = [252; 32];
    assert_ne!(GENESIS_ADDR, key_1);
    assert_ne!(GENESIS_ADDR, key_2);
    assert_ne!(GENESIS_ADDR, key_3);
    // Basic deploy with single key
    let result = InMemoryWasmTestBuilder::default()
        .run_genesis(GENESIS_ADDR, HashMap::new())
        .exec_with_args_and_keys(
            GENESIS_ADDR,
            STANDARD_PAYMENT_CONTRACT,
            (U512::from(MAX_PAYMENT),),
            "authorized_keys.wasm",
            (Weight::new(1), Weight::new(1)), //args
            1,                                // blocktime
            [1u8; 32],                        //deploy hash
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

    assert_eq!(
        message,
        format!("{}", engine_state::error::Error::AuthorizationError)
    )
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
    let result1 = InMemoryWasmTestBuilder::default()
        .run_genesis(GENESIS_ADDR, HashMap::new())
        // Reusing a test contract that would add new key
        .exec_with_args(
            GENESIS_ADDR,
            STANDARD_PAYMENT_CONTRACT,
            (U512::from(MAX_PAYMENT),),
            "add_update_associated_key.wasm",
            (PublicKey::new(key_1),),
            DEFAULT_BLOCK_TIME,
            [1u8; 32], //deploy hash
        )
        .expect_success()
        .commit()
        .exec_with_args(
            GENESIS_ADDR,
            STANDARD_PAYMENT_CONTRACT,
            (U512::from(MAX_PAYMENT),),
            "add_update_associated_key.wasm",
            (PublicKey::new(key_2),),
            DEFAULT_BLOCK_TIME,
            [2u8; 32], //deploy hash
        )
        .expect_success()
        .commit()
        .exec_with_args(
            GENESIS_ADDR,
            STANDARD_PAYMENT_CONTRACT,
            (U512::from(MAX_PAYMENT),),
            "add_update_associated_key.wasm",
            (PublicKey::new(key_3),),
            DEFAULT_BLOCK_TIME,
            [3u8; 32], //deploy hash
        )
        .expect_success()
        .commit()
        // This should execute successfuly - change deploy and key management
        // thresholds.
        .exec_with_args_and_keys(
            GENESIS_ADDR,
            STANDARD_PAYMENT_CONTRACT,
            (U512::from(MAX_PAYMENT),),
            "authorized_keys.wasm",
            // Deploy threshold is equal to 3, keymgmnt is still 1.
            // Even after verifying weights and thresholds to not
            // lock out the account, those values should work as
            // account now has 1. identity key with weight=1 and
            // a key with weight=2.
            (Weight::new(4), Weight::new(3)), //args
            DEFAULT_BLOCK_TIME,
            [4u8; 32], //deploy hash
            vec![PublicKey::new(GENESIS_ADDR)],
        )
        .expect_success()
        .commit()
        .finish();

    // With deploy threshold == 3 using single secondary key
    // with weight == 2 should raise deploy authorization failure.
    let result2 = InMemoryWasmTestBuilder::from_result(result1)
        .exec_with_args_and_keys(
            GENESIS_ADDR,
            STANDARD_PAYMENT_CONTRACT,
            (U512::from(MAX_PAYMENT),),
            "authorized_keys.wasm",
            // Next deploy will see deploy threshold == 4, keymgmnt == 5
            (Weight::new(5), Weight::new(4)), //args
            DEFAULT_BLOCK_TIME,
            [5u8; 32], //deploy hash
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

        assert!(deploy_result.has_precondition_failure());
        let message = deploy_result.get_precondition_failure().get_message();
        assert!(message.contains(&format!(
            "{}",
            execution::Error::DeploymentAuthorizationFailure
        )))
    }

    // identity key (w: 1) and key_1 (w: 2) passes threshold of 3
    let result3 = InMemoryWasmTestBuilder::from_result(result2)
        .exec_with_args_and_keys(
            GENESIS_ADDR,
            STANDARD_PAYMENT_CONTRACT,
            (U512::from(MAX_PAYMENT),),
            "authorized_keys.wasm",
            // change deployment threshold to 4
            (Weight::new(6), Weight::new(5)), //args
            DEFAULT_BLOCK_TIME,
            [6u8; 32], //deploy hash
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
    let result4 = InMemoryWasmTestBuilder::from_result(result3)
        .exec_with_args_and_keys(
            GENESIS_ADDR,
            STANDARD_PAYMENT_CONTRACT,
            (U512::from(MAX_PAYMENT),),
            "authorized_keys.wasm",
            (Weight::new(0), Weight::new(0)), //args
            DEFAULT_BLOCK_TIME,
            [7u8; 32],
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

        assert!(deploy_result.has_precondition_failure());
        let message = deploy_result.get_precondition_failure().get_message();
        assert!(message.contains(&format!(
            "{}",
            execution::Error::DeploymentAuthorizationFailure
        )))
    }

    // success: identity key weight + key_1 weight + key_2 weight >= deployment
    // threshold
    InMemoryWasmTestBuilder::from_result(result4)
        .exec_with_args_and_keys(
            GENESIS_ADDR,
            STANDARD_PAYMENT_CONTRACT,
            (U512::from(MAX_PAYMENT),),
            "authorized_keys.wasm",
            (Weight::new(0), Weight::new(0)), //args
            DEFAULT_BLOCK_TIME,
            [8u8; 32],
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
    let result1 = InMemoryWasmTestBuilder::default()
        .run_genesis(GENESIS_ADDR, HashMap::new())
        // Reusing a test contract that would add new key
        .exec_with_args(
            GENESIS_ADDR,
            STANDARD_PAYMENT_CONTRACT,
            (U512::from(MAX_PAYMENT),),
            "add_update_associated_key.wasm",
            (PublicKey::new(key_1),),
            DEFAULT_BLOCK_TIME,
            [1u8; 32], // deploy hash
        )
        .expect_success()
        .commit()
        .exec_with_args(
            GENESIS_ADDR,
            STANDARD_PAYMENT_CONTRACT,
            (U512::from(MAX_PAYMENT),),
            "add_update_associated_key.wasm",
            (PublicKey::new(key_2),),
            DEFAULT_BLOCK_TIME,
            [2u8; 32], // deploy hash
        )
        .expect_success()
        .commit()
        .finish();

    // key_1 (w: 2) key_2 (w: 2) each passes default threshold of 1
    InMemoryWasmTestBuilder::from_result(result1)
        .exec_with_args_and_keys(
            GENESIS_ADDR,
            STANDARD_PAYMENT_CONTRACT,
            (U512::from(MAX_PAYMENT),),
            "authorized_keys.wasm",
            (Weight::new(0), Weight::new(0)), //args
            DEFAULT_BLOCK_TIME,
            [3u8; 32], // deploy hash
            // change deployment threshold to 4
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
    let result1 = InMemoryWasmTestBuilder::default()
        .run_genesis(GENESIS_ADDR, HashMap::new())
        // Reusing a test contract that would add new key
        .exec_with_args(
            GENESIS_ADDR,
            STANDARD_PAYMENT_CONTRACT,
            (U512::from(MAX_PAYMENT),),
            "add_update_associated_key.wasm",
            (PublicKey::new(key_1),),
            DEFAULT_BLOCK_TIME,
            [1u8; 32], // deploy hash
        )
        .expect_success()
        .commit()
        .exec_with_args_and_keys(
            GENESIS_ADDR,
            STANDARD_PAYMENT_CONTRACT,
            (U512::from(MAX_PAYMENT),),
            "authorized_keys.wasm",
            // change deployment threshold to 3
            (Weight::new(4), Weight::new(3)), //args
            DEFAULT_BLOCK_TIME,
            [2u8; 32], // deploy hash
            vec![PublicKey::new(GENESIS_ADDR)],
        )
        .expect_success()
        .commit()
        .finish();

    // success: identity key weight + key_1 weight + key_2 weight >= deployment
    // threshold
    let final_result = InMemoryWasmTestBuilder::from_result(result1)
        .exec_with_args_and_keys(
            GENESIS_ADDR,
            STANDARD_PAYMENT_CONTRACT,
            (U512::from(MAX_PAYMENT),),
            "authorized_keys.wasm",
            (Weight::new(0), Weight::new(0)), //args
            DEFAULT_BLOCK_TIME,
            [3u8; 32], // deploy hash
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

    assert!(
        deploy_result.has_precondition_failure(),
        "{:?}",
        deploy_result
    );
    let message = deploy_result.get_precondition_failure().get_message();
    assert!(message.contains(&format!(
        "{}",
        execution::Error::DeploymentAuthorizationFailure
    )))
}
