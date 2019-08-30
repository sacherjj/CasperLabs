use std::collections::HashMap;

use contract_ffi::key::Key;
use contract_ffi::value::account::PublicKey;
use contract_ffi::value::{Value, U512};

use crate::support::test_support::{WasmTestBuilder, DEFAULT_BLOCK_TIME};

const CREATE: &str = "create";

const GENESIS_ADDR: [u8; 32] = [0u8; 32];
const ACCOUNT_1_ADDR: [u8; 32] = [1u8; 32];
const ACCOUNT_2_ADDR: [u8; 32] = [2u8; 32];
const INITIAL_AMOUNT: u32 = 500_000;

const CONTRACT_TRANSFER: &str = "transfer_purse_to_account.wasm";
const CONTRACT_CREATE: &str = "ee_572_regression_create.wasm";
const CONTRACT_ESCALATE: &str = "ee_572_regression_escalate.wasm";

#[ignore]
#[test]
fn should_run_ee_572_regression() {
    let account_amount = U512::from(INITIAL_AMOUNT);
    let account_1 = PublicKey::new(ACCOUNT_1_ADDR);
    let account_2 = PublicKey::new(ACCOUNT_2_ADDR);
    let account_1_creation_args = (account_1, account_amount);
    let account_2_creation_args = (account_2, account_amount);

    // This test runs a contract that's after every call extends the same key with
    // more data
    let mut builder = WasmTestBuilder::default();

    // Create Accounts
    builder
        .run_genesis(GENESIS_ADDR, HashMap::new())
        .exec_with_args(
            GENESIS_ADDR,
            CONTRACT_TRANSFER,
            DEFAULT_BLOCK_TIME,
            1,
            account_1_creation_args,
        )
        .expect_success()
        .commit()
        .exec_with_args(
            GENESIS_ADDR,
            CONTRACT_TRANSFER,
            DEFAULT_BLOCK_TIME,
            2,
            account_2_creation_args,
        )
        .expect_success()
        .commit();

    // Store the creation contract
    builder
        .exec(ACCOUNT_1_ADDR, CONTRACT_CREATE, DEFAULT_BLOCK_TIME, 1)
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
            CONTRACT_ESCALATE,
            DEFAULT_BLOCK_TIME,
            1,
            (contract,),
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
