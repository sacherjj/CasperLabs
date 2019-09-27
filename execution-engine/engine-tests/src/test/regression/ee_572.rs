use contract_ffi::key::Key;
use contract_ffi::value::account::PublicKey;
use contract_ffi::value::{Value, U512};
use engine_core::engine_state::MAX_PAYMENT;

use crate::support::test_support::{
    DeployBuilder, ExecRequestBuilder, InMemoryWasmTestBuilder, STANDARD_PAYMENT_CONTRACT,
};
use crate::test::{DEFAULT_ACCOUNT_ADDR, DEFAULT_GENESIS_CONFIG, DEFAULT_PAYMENT};

const CREATE: &str = "create";

const ACCOUNT_1_ADDR: [u8; 32] = [1u8; 32];
const ACCOUNT_2_ADDR: [u8; 32] = [2u8; 32];

const CONTRACT_TRANSFER: &str = "transfer_purse_to_account.wasm";
const CONTRACT_CREATE: &str = "ee_572_regression_create.wasm";
const CONTRACT_ESCALATE: &str = "ee_572_regression_escalate.wasm";

#[ignore]
#[test]
fn should_run_ee_572_regression() {
    let account_amount: U512 = *DEFAULT_PAYMENT + U512::from(100);
    let account_1_creation_args = (ACCOUNT_1_ADDR, account_amount);
    let account_2_creation_args = (ACCOUNT_2_ADDR, account_amount);

    // This test runs a contract that's after every call extends the same key with
    // more data
    let mut builder = InMemoryWasmTestBuilder::default();

    let exec_request_1 = {
        let deploy = DeployBuilder::new()
            .with_address(DEFAULT_ACCOUNT_ADDR)
            .with_payment_code(STANDARD_PAYMENT_CONTRACT, (*DEFAULT_PAYMENT,))
            .with_session_code(CONTRACT_TRANSFER, account_1_creation_args)
            .with_deploy_hash([1u8; 32])
            .with_authorization_keys(&[PublicKey::new(DEFAULT_ACCOUNT_ADDR)])
            .build();
        ExecRequestBuilder::from_deploy(deploy).build()
    };
    let exec_request_2 = {
        let deploy = DeployBuilder::new()
            .with_address(DEFAULT_ACCOUNT_ADDR)
            .with_payment_code(STANDARD_PAYMENT_CONTRACT, (*DEFAULT_PAYMENT,))
            .with_session_code(CONTRACT_TRANSFER, account_2_creation_args)
            .with_deploy_hash([2u8; 32])
            .with_authorization_keys(&[PublicKey::new(DEFAULT_ACCOUNT_ADDR)])
            .build();
        ExecRequestBuilder::from_deploy(deploy).build()
    };

    let exec_request_3 = {
        let deploy = DeployBuilder::new()
            .with_address(ACCOUNT_1_ADDR)
            .with_payment_code(STANDARD_PAYMENT_CONTRACT, (U512::from(MAX_PAYMENT),))
            .with_session_code(CONTRACT_CREATE, account_2_creation_args)
            .with_deploy_hash([3u8; 32])
            .with_authorization_keys(&[PublicKey::new(ACCOUNT_1_ADDR)])
            .build();
        ExecRequestBuilder::from_deploy(deploy).build()
    };

    // Create Accounts
    builder
        .run_genesis(&DEFAULT_GENESIS_CONFIG)
        .exec_with_exec_request(exec_request_1)
        .expect_success()
        .commit()
        .exec_with_exec_request(exec_request_2)
        .expect_success()
        .commit();

    // Store the creation contract
    builder
        .exec_with_exec_request(exec_request_3)
        .expect_success()
        .commit();

    let contract: Key = {
        let account = match builder.query(None, Key::Account(ACCOUNT_1_ADDR), &[]) {
            Some(Value::Account(account)) => account,
            _ => panic!("Could not find account at: {:?}", ACCOUNT_1_ADDR),
        };
        *account
            .urefs_lookup()
            .get(CREATE)
            .expect("Could not find contract pointer")
    };

    let exec_request_4 = {
        let deploy = DeployBuilder::new()
            .with_address(ACCOUNT_2_ADDR)
            .with_payment_code(STANDARD_PAYMENT_CONTRACT, (U512::from(MAX_PAYMENT),))
            .with_session_code(CONTRACT_ESCALATE, (contract,))
            .with_deploy_hash([3u8; 32])
            .with_authorization_keys(&[PublicKey::new(ACCOUNT_2_ADDR)])
            .build();
        ExecRequestBuilder::from_deploy(deploy).build()
    };

    // Attempt to forge a new URef with escalated privileges
    let response = builder
        .exec_with_exec_request(exec_request_4)
        .get_exec_response(3)
        .expect("should have a response")
        .to_owned();

    let error_message = {
        let execution_result = crate::support::test_support::get_success_result(&response);
        crate::support::test_support::get_error_message(execution_result)
    };

    assert!(error_message.contains("ForgedReference"));
}
