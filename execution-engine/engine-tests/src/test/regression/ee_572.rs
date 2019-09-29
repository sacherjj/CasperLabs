use contract_ffi::key::Key;
use contract_ffi::value::{Value, U512};
use engine_core::engine_state::MAX_PAYMENT;

use crate::support::test_support::{
    InMemoryWasmTestBuilder, DEFAULT_BLOCK_TIME, STANDARD_PAYMENT_CONTRACT,
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

    // Create Accounts
    builder
        .run_genesis(&DEFAULT_GENESIS_CONFIG)
        .exec_with_args(
            DEFAULT_ACCOUNT_ADDR,
            STANDARD_PAYMENT_CONTRACT,
            (*DEFAULT_PAYMENT,),
            CONTRACT_TRANSFER,
            account_1_creation_args,
            DEFAULT_BLOCK_TIME,
            [1u8; 32],
        )
        .expect_success()
        .commit()
        .exec_with_args(
            DEFAULT_ACCOUNT_ADDR,
            STANDARD_PAYMENT_CONTRACT,
            (*DEFAULT_PAYMENT,),
            CONTRACT_TRANSFER,
            account_2_creation_args,
            DEFAULT_BLOCK_TIME,
            [2u8; 32],
        )
        .expect_success()
        .commit();

    // Store the creation contract
    builder
        .exec(
            ACCOUNT_1_ADDR,
            CONTRACT_CREATE,
            DEFAULT_BLOCK_TIME,
            [3u8; 32],
        )
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

    // Attempt to forge a new URef with escalated privileges
    let response = builder
        .exec_with_args(
            ACCOUNT_2_ADDR,
            STANDARD_PAYMENT_CONTRACT,
            (U512::from(MAX_PAYMENT),),
            CONTRACT_ESCALATE,
            (contract,),
            DEFAULT_BLOCK_TIME,
            [4u8; 32],
        )
        .get_exec_response(3)
        .expect("should have a response")
        .to_owned();

    let error_message = {
        let execution_result = crate::support::test_support::get_success_result(&response);
        crate::support::test_support::get_error_message(execution_result)
    };

    assert!(error_message.contains("ForgedReference"));
}
