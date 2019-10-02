use contract_ffi::key::Key;
use contract_ffi::value::{Value, U512};

use crate::support::test_support::{ExecuteRequestBuilder, InMemoryWasmTestBuilder};
use crate::test::{DEFAULT_ACCOUNT_ADDR, DEFAULT_GENESIS_CONFIG, DEFAULT_PAYMENT};

const CONTRACT_CREATE: &str = "ee_572_regression_create";
const CONTRACT_ESCALATE: &str = "ee_572_regression_escalate";
const CONTRACT_TRANSFER: &str = "transfer_purse_to_account";
const CREATE: &str = "create";

const ACCOUNT_1_ADDR: [u8; 32] = [1u8; 32];
const ACCOUNT_2_ADDR: [u8; 32] = [2u8; 32];

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
        let contract_name = format!("{}.wasm", CONTRACT_TRANSFER);
        ExecuteRequestBuilder::standard(
            DEFAULT_ACCOUNT_ADDR,
            &contract_name,
            account_1_creation_args,
        )
    };
    let exec_request_2 = {
        let contract_name = format!("{}.wasm", CONTRACT_TRANSFER);
        ExecuteRequestBuilder::standard(
            DEFAULT_ACCOUNT_ADDR,
            &contract_name,
            account_2_creation_args,
        )
    };

    let exec_request_3 = {
        let contract_name = format!("{}.wasm", CONTRACT_CREATE);
        ExecuteRequestBuilder::standard(ACCOUNT_1_ADDR, &contract_name, account_2_creation_args)
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
            .named_keys()
            .get(CREATE)
            .expect("Could not find contract pointer")
    };

    let exec_request_4 = {
        let contract_name = format!("{}.wasm", CONTRACT_ESCALATE);
        ExecuteRequestBuilder::standard(ACCOUNT_2_ADDR, &contract_name, (contract,))
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
