use crate::support::test_support::{
    DeployBuilder, ExecRequestBuilder, InMemoryWasmTestBuilder, STANDARD_PAYMENT_CONTRACT,
};
use crate::test::{DEFAULT_ACCOUNT_ADDR, DEFAULT_GENESIS_CONFIG};
use contract_ffi::value::account::{PublicKey, Weight};
use contract_ffi::value::U512;
use engine_core::engine_state::{self, MAX_PAYMENT};
use engine_core::execution;

#[ignore]
#[test]
fn should_deploy_with_authorized_identity_key() {
    let exec_request = {
        let deploy = DeployBuilder::new()
            .with_address(DEFAULT_ACCOUNT_ADDR)
            .with_payment_code(STANDARD_PAYMENT_CONTRACT, (U512::from(MAX_PAYMENT),))
            .with_session_code("authorized_keys.wasm", (Weight::new(1), Weight::new(1)))
            .with_deploy_hash([1u8; 32])
            .with_authorization_keys(&[PublicKey::new(DEFAULT_ACCOUNT_ADDR)])
            .build();
        ExecRequestBuilder::from_deploy(deploy).build()
    };
    // Basic deploy with single key
    InMemoryWasmTestBuilder::default()
        .run_genesis(&DEFAULT_GENESIS_CONFIG)
        .exec_with_exec_request(exec_request)
        .commit()
        .expect_success();
}

#[ignore]
#[test]
fn should_raise_auth_failure_with_invalid_key() {
    // tests that authorized keys that does not belong to account raises
    // AuthorizationError
    let key_1 = [254; 32];
    assert_ne!(DEFAULT_ACCOUNT_ADDR, key_1);

    let exec_request = {
        let deploy = DeployBuilder::new()
            .with_address(DEFAULT_ACCOUNT_ADDR)
            .with_payment_code(STANDARD_PAYMENT_CONTRACT, (U512::from(MAX_PAYMENT),))
            .with_session_code("authorized_keys.wasm", (Weight::new(1), Weight::new(1)))
            .with_deploy_hash([1u8; 32])
            .with_authorization_keys(&[PublicKey::new(key_1)])
            .build();
        ExecRequestBuilder::from_deploy(deploy).build()
    };

    // Basic deploy with single key
    let result = InMemoryWasmTestBuilder::default()
        .run_genesis(&DEFAULT_GENESIS_CONFIG)
        .exec_with_exec_request(exec_request)
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
    assert_ne!(DEFAULT_ACCOUNT_ADDR, key_1);
    assert_ne!(DEFAULT_ACCOUNT_ADDR, key_2);
    assert_ne!(DEFAULT_ACCOUNT_ADDR, key_3);

    let exec_request = {
        let deploy = DeployBuilder::new()
            .with_address(DEFAULT_ACCOUNT_ADDR)
            .with_payment_code(STANDARD_PAYMENT_CONTRACT, (U512::from(MAX_PAYMENT),))
            .with_session_code("authorized_keys.wasm", (Weight::new(1), Weight::new(1)))
            .with_deploy_hash([1u8; 32])
            .with_authorization_keys(&[
                PublicKey::new(key_2),
                PublicKey::new(key_1),
                PublicKey::new(key_3),
            ])
            .build();
        ExecRequestBuilder::from_deploy(deploy).build()
    };

    // Basic deploy with single key
    let result = InMemoryWasmTestBuilder::default()
        .run_genesis(&DEFAULT_GENESIS_CONFIG)
        .exec_with_exec_request(exec_request)
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
    assert_ne!(DEFAULT_ACCOUNT_ADDR, key_1);
    assert_ne!(DEFAULT_ACCOUNT_ADDR, key_2);
    assert_ne!(DEFAULT_ACCOUNT_ADDR, key_3);

    let exec_request_1 = {
        let deploy = DeployBuilder::new()
            .with_address(DEFAULT_ACCOUNT_ADDR)
            .with_payment_code(STANDARD_PAYMENT_CONTRACT, (U512::from(MAX_PAYMENT),))
            .with_session_code("add_update_associated_key.wasm", (PublicKey::new(key_1),))
            .with_deploy_hash([1u8; 32])
            .with_authorization_keys(&[PublicKey::new(DEFAULT_ACCOUNT_ADDR)])
            .build();
        ExecRequestBuilder::from_deploy(deploy).build()
    };
    let exec_request_2 = {
        let deploy = DeployBuilder::new()
            .with_address(DEFAULT_ACCOUNT_ADDR)
            .with_payment_code(STANDARD_PAYMENT_CONTRACT, (U512::from(MAX_PAYMENT),))
            .with_session_code("add_update_associated_key.wasm", (PublicKey::new(key_2),))
            .with_deploy_hash([2u8; 32])
            .with_authorization_keys(&[PublicKey::new(DEFAULT_ACCOUNT_ADDR)])
            .build();
        ExecRequestBuilder::from_deploy(deploy).build()
    };
    let exec_request_3 = {
        let deploy = DeployBuilder::new()
            .with_address(DEFAULT_ACCOUNT_ADDR)
            .with_payment_code(STANDARD_PAYMENT_CONTRACT, (U512::from(MAX_PAYMENT),))
            .with_session_code("add_update_associated_key.wasm", (PublicKey::new(key_3),))
            .with_deploy_hash([3u8; 32])
            .with_authorization_keys(&[PublicKey::new(DEFAULT_ACCOUNT_ADDR)])
            .build();
        ExecRequestBuilder::from_deploy(deploy).build()
    };
    let exec_request_4 = {
        let deploy = DeployBuilder::new()
            .with_address(DEFAULT_ACCOUNT_ADDR)
            .with_payment_code(STANDARD_PAYMENT_CONTRACT, (U512::from(MAX_PAYMENT),))
            // Deploy threshold is equal to 3, keymgmnt is still 1.
            // Even after verifying weights and thresholds to not
            // lock out the account, those values should work as
            // account now has 1. identity key with weight=1 and
            // a key with weight=2.
            .with_session_code("authorized_keys.wasm", (Weight::new(4), Weight::new(3)))
            .with_deploy_hash([4u8; 32])
            .with_authorization_keys(&[PublicKey::new(DEFAULT_ACCOUNT_ADDR)])
            .build();
        ExecRequestBuilder::from_deploy(deploy).build()
    };
    // Basic deploy with single key
    let result1 = InMemoryWasmTestBuilder::default()
        .run_genesis(&DEFAULT_GENESIS_CONFIG)
        // Reusing a test contract that would add new key
        .exec_with_exec_request(exec_request_1)
        .expect_success()
        .commit()
        .exec_with_exec_request(exec_request_2)
        .expect_success()
        .commit()
        .exec_with_exec_request(exec_request_3)
        .expect_success()
        .commit()
        // This should execute successfuly - change deploy and key management
        // thresholds.
        .exec_with_exec_request(exec_request_4)
        .expect_success()
        .commit()
        .finish();

    let exec_request_5 = {
        let deploy = DeployBuilder::new()
            .with_address(DEFAULT_ACCOUNT_ADDR)
            .with_payment_code(STANDARD_PAYMENT_CONTRACT, (U512::from(MAX_PAYMENT),))
            // Next deploy will see deploy threshold == 4, keymgmnt == 5
            .with_session_code(
                "authorized_keys.wasm",
                (Weight::new(5), Weight::new(4)), //args
            )
            .with_deploy_hash([5u8; 32])
            .with_authorization_keys(&[PublicKey::new(key_1)])
            .build();
        ExecRequestBuilder::from_deploy(deploy).build()
    };

    // With deploy threshold == 3 using single secondary key
    // with weight == 2 should raise deploy authorization failure.
    let result2 = InMemoryWasmTestBuilder::from_result(result1)
        .exec_with_exec_request(exec_request_5)
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
    let exec_request_6 = {
        let deploy = DeployBuilder::new()
            .with_address(DEFAULT_ACCOUNT_ADDR)
            .with_payment_code(STANDARD_PAYMENT_CONTRACT, (U512::from(MAX_PAYMENT),))
            // change deployment threshold to 4
            .with_session_code("authorized_keys.wasm", (Weight::new(6), Weight::new(5)))
            .with_deploy_hash([6u8; 32])
            .with_authorization_keys(&[
                PublicKey::new(DEFAULT_ACCOUNT_ADDR),
                PublicKey::new(key_1),
                PublicKey::new(key_2),
                PublicKey::new(key_3),
            ])
            .build();
        ExecRequestBuilder::from_deploy(deploy).build()
    };
    // identity key (w: 1) and key_1 (w: 2) passes threshold of 3
    let result3 = InMemoryWasmTestBuilder::from_result(result2)
        .exec_with_exec_request(exec_request_6)
        .expect_success()
        .commit()
        .finish();

    let exec_request_7 = {
        let deploy = DeployBuilder::new()
            .with_address(DEFAULT_ACCOUNT_ADDR)
            .with_payment_code(STANDARD_PAYMENT_CONTRACT, (U512::from(MAX_PAYMENT),))
            // change deployment threshold to 4
            .with_session_code(
                "authorized_keys.wasm",
                (Weight::new(0), Weight::new(0)), //args
            )
            .with_deploy_hash([6u8; 32])
            .with_authorization_keys(&[PublicKey::new(key_2), PublicKey::new(key_1)])
            .build();
        ExecRequestBuilder::from_deploy(deploy).build()
    };

    // deployment threshold is now 4
    // failure: key_2 weight + key_1 weight < deployment threshold
    let result4 = InMemoryWasmTestBuilder::from_result(result3)
        .exec_with_exec_request(exec_request_7)
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

    let exec_request_8 = {
        let deploy = DeployBuilder::new()
            .with_address(DEFAULT_ACCOUNT_ADDR)
            .with_payment_code(STANDARD_PAYMENT_CONTRACT, (U512::from(MAX_PAYMENT),))
            // change deployment threshold to 4
            .with_session_code(
                "authorized_keys.wasm",
                (Weight::new(0), Weight::new(0)), //args
            )
            .with_deploy_hash([8u8; 32])
            .with_authorization_keys(&[
                PublicKey::new(DEFAULT_ACCOUNT_ADDR),
                PublicKey::new(key_1),
                PublicKey::new(key_2),
                PublicKey::new(key_3),
            ])
            .build();
        ExecRequestBuilder::from_deploy(deploy).build()
    };

    // success: identity key weight + key_1 weight + key_2 weight >= deployment
    // threshold
    InMemoryWasmTestBuilder::from_result(result4)
        .exec_with_exec_request(exec_request_8)
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
    assert_ne!(DEFAULT_ACCOUNT_ADDR, key_1);
    assert_ne!(DEFAULT_ACCOUNT_ADDR, key_2);

    let exec_request_1 = {
        let deploy = DeployBuilder::new()
            .with_address(DEFAULT_ACCOUNT_ADDR)
            .with_payment_code(STANDARD_PAYMENT_CONTRACT, (U512::from(MAX_PAYMENT),))
            .with_session_code("add_update_associated_key.wasm", (PublicKey::new(key_1),))
            .with_deploy_hash([1u8; 32])
            .with_authorization_keys(&[PublicKey::new(DEFAULT_ACCOUNT_ADDR)])
            .build();
        ExecRequestBuilder::from_deploy(deploy).build()
    };
    let exec_request_2 = {
        let deploy = DeployBuilder::new()
            .with_address(DEFAULT_ACCOUNT_ADDR)
            .with_payment_code(STANDARD_PAYMENT_CONTRACT, (U512::from(MAX_PAYMENT),))
            .with_session_code("add_update_associated_key.wasm", (PublicKey::new(key_2),))
            .with_deploy_hash([2u8; 32])
            .with_authorization_keys(&[PublicKey::new(DEFAULT_ACCOUNT_ADDR)])
            .build();
        ExecRequestBuilder::from_deploy(deploy).build()
    };
    // Basic deploy with single key
    let result1 = InMemoryWasmTestBuilder::default()
        .run_genesis(&DEFAULT_GENESIS_CONFIG)
        // Reusing a test contract that would add new key
        .exec_with_exec_request(exec_request_1)
        .expect_success()
        .commit()
        .exec_with_exec_request(exec_request_2)
        .expect_success()
        .commit()
        .finish();

    // key_1 (w: 2) key_2 (w: 2) each passes default threshold of 1

    let exec_request_3 = {
        let deploy = DeployBuilder::new()
            .with_address(DEFAULT_ACCOUNT_ADDR)
            .with_payment_code(STANDARD_PAYMENT_CONTRACT, (U512::from(MAX_PAYMENT),))
            .with_session_code("authorized_keys.wasm", (Weight::new(0), Weight::new(0)))
            .with_deploy_hash([1u8; 32])
            .with_authorization_keys(&[PublicKey::new(key_2), PublicKey::new(key_1)])
            .build();
        ExecRequestBuilder::from_deploy(deploy).build()
    };
    InMemoryWasmTestBuilder::from_result(result1)
        .exec_with_exec_request(exec_request_3)
        .expect_success()
        .commit();
}

#[ignore]
#[test]
fn should_not_authorize_deploy_with_duplicated_keys() {
    // tests that authorized keys needs sufficient cumulative weight
    // and each of the associated keys is greater than threshold
    let key_1 = [254; 32];
    assert_ne!(DEFAULT_ACCOUNT_ADDR, key_1);

    let exec_request_1 = {
        let deploy = DeployBuilder::new()
            .with_address(DEFAULT_ACCOUNT_ADDR)
            .with_payment_code(STANDARD_PAYMENT_CONTRACT, (U512::from(MAX_PAYMENT),))
            .with_session_code("add_update_associated_key.wasm", (PublicKey::new(key_1),))
            .with_deploy_hash([1u8; 32])
            .with_authorization_keys(&[PublicKey::new(DEFAULT_ACCOUNT_ADDR)])
            .build();
        ExecRequestBuilder::from_deploy(deploy).build()
    };

    let exec_request_2 = {
        let deploy = DeployBuilder::new()
            .with_address(DEFAULT_ACCOUNT_ADDR)
            .with_payment_code(STANDARD_PAYMENT_CONTRACT, (U512::from(MAX_PAYMENT),))
            // change deployment threshold to 3
            .with_session_code("authorized_keys.wasm", (Weight::new(4), Weight::new(3)))
            .with_deploy_hash([2u8; 32])
            .with_authorization_keys(&[PublicKey::new(DEFAULT_ACCOUNT_ADDR)])
            .build();
        ExecRequestBuilder::from_deploy(deploy).build()
    };
    // Basic deploy with single key
    let result1 = InMemoryWasmTestBuilder::default()
        .run_genesis(&DEFAULT_GENESIS_CONFIG)
        // Reusing a test contract that would add new key
        .exec_with_exec_request(exec_request_1)
        .expect_success()
        .commit()
        .exec_with_exec_request(exec_request_2)
        .expect_success()
        .commit()
        .finish();

    let exec_request_3 = {
        let deploy = DeployBuilder::new()
            .with_address(DEFAULT_ACCOUNT_ADDR)
            .with_payment_code(STANDARD_PAYMENT_CONTRACT, (U512::from(MAX_PAYMENT),))
            .with_session_code("authorized_keys.wasm", (Weight::new(0), Weight::new(0)))
            .with_deploy_hash([3u8; 32])
            .with_authorization_keys(&[
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
            ])
            .build();
        ExecRequestBuilder::from_deploy(deploy).build()
    };
    // success: identity key weight + key_1 weight + key_2 weight >= deployment
    // threshold
    let final_result = InMemoryWasmTestBuilder::from_result(result1)
        .exec_with_exec_request(exec_request_3)
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
