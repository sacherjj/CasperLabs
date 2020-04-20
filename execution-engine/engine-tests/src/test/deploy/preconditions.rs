use assert_matches::assert_matches;

use engine_core::engine_state::Error;
use engine_test_support::{
    internal::{
        utils, DeployItemBuilder, ExecuteRequestBuilder, InMemoryWasmTestBuilder,
        DEFAULT_RUN_GENESIS_REQUEST, STANDARD_PAYMENT_CONTRACT,
    },
    DEFAULT_ACCOUNT_ADDR,
};
use types::{account::PublicKey, U512};

const ACCOUNT_1_ADDR: PublicKey = PublicKey::ed25519_from([42u8; 32]);

#[ignore]
#[test]
fn should_raise_precondition_authorization_failure_invalid_account() {
    let account_1_public_key = ACCOUNT_1_ADDR;
    let nonexistent_account_addr = PublicKey::ed25519_from([99u8; 32]);
    let payment_purse_amount = 10_000_000;
    let transferred_amount = 1;

    let exec_request = {
        let deploy = DeployItemBuilder::new()
            .with_address(DEFAULT_ACCOUNT_ADDR)
            .with_deploy_hash([1; 32])
            .with_session_code(
                "transfer_purse_to_account.wasm",
                (account_1_public_key, U512::from(transferred_amount)),
            )
            .with_address(nonexistent_account_addr)
            .with_payment_code("standard_payment.wasm", (U512::from(payment_purse_amount),))
            .with_authorization_keys(&[nonexistent_account_addr])
            .build();

        ExecuteRequestBuilder::new().push_deploy(deploy).build()
    };

    let transfer_result = InMemoryWasmTestBuilder::default()
        .run_genesis(&DEFAULT_RUN_GENESIS_REQUEST)
        .exec(exec_request)
        .finish();

    let response = transfer_result
        .builder()
        .get_exec_response(0)
        .expect("there should be a response");

    let precondition_failure = utils::get_precondition_failure(response);
    assert_matches!(precondition_failure, Error::Authorization);
}

#[ignore]
#[test]
fn should_raise_precondition_authorization_failure_empty_authorized_keys() {
    let empty_keys: [PublicKey; 0] = [];
    let exec_request = {
        let deploy = DeployItemBuilder::new()
            .with_address(DEFAULT_ACCOUNT_ADDR)
            .with_session_code("do_nothing.wasm", ())
            .with_payment_code(STANDARD_PAYMENT_CONTRACT, ())
            .with_deploy_hash([1; 32])
            // empty authorization keys to force error
            .with_authorization_keys(&empty_keys)
            .build();

        ExecuteRequestBuilder::new().push_deploy(deploy).build()
    };

    let transfer_result = InMemoryWasmTestBuilder::default()
        .run_genesis(&DEFAULT_RUN_GENESIS_REQUEST)
        .exec(exec_request)
        .finish();

    let response = transfer_result
        .builder()
        .get_exec_response(0)
        .expect("there should be a response");

    let precondition_failure = utils::get_precondition_failure(response);
    assert_matches!(precondition_failure, Error::Authorization);
}

#[ignore]
#[test]
fn should_raise_precondition_authorization_failure_invalid_authorized_keys() {
    let account_1_public_key = ACCOUNT_1_ADDR;
    let nonexistent_account_addr = PublicKey::ed25519_from([99u8; 32]);
    let payment_purse_amount = 10_000_000;
    let transferred_amount = 1;

    let exec_request = {
        let deploy = DeployItemBuilder::new()
            .with_address(DEFAULT_ACCOUNT_ADDR)
            .with_deploy_hash([1; 32])
            .with_session_code(
                "transfer_purse_to_account.wasm",
                (account_1_public_key, U512::from(transferred_amount)),
            )
            .with_payment_code("standard_payment.wasm", (U512::from(payment_purse_amount),))
            // invalid authorization key to force error
            .with_authorization_keys(&[nonexistent_account_addr])
            .build();

        ExecuteRequestBuilder::new().push_deploy(deploy).build()
    };

    let transfer_result = InMemoryWasmTestBuilder::default()
        .run_genesis(&DEFAULT_RUN_GENESIS_REQUEST)
        .exec(exec_request)
        .finish();

    let response = transfer_result
        .builder()
        .get_exec_response(0)
        .expect("there should be a response");

    let precondition_failure = utils::get_precondition_failure(response);
    assert_matches!(precondition_failure, Error::Authorization);
}
