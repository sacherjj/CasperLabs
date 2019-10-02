use contract_ffi::value::U512;

use crate::support::test_support::{ExecuteRequestBuilder, InMemoryWasmTestBuilder};
use crate::test::{DEFAULT_ACCOUNT_ADDR, DEFAULT_GENESIS_CONFIG, DEFAULT_PAYMENT};

const CONTRACT_POS_GET_PAYMENT_PURSE: &str = "pos_get_payment_purse";
const CONTRACT_TRANSFER_PURSE_TO_ACCOUNT: &str = "transfer_purse_to_account";
const ACCOUNT_1_ADDR: [u8; 32] = [1u8; 32];
const ACCOUNT_1_INITIAL_BALANCE: u64 = 100_000_000 + 100;

#[ignore]
#[test]
fn should_run_get_payment_purse_contract_default_account() {
    let exec_request = {
        let contract_name = format!("{}.wasm", CONTRACT_POS_GET_PAYMENT_PURSE);
        ExecuteRequestBuilder::standard(DEFAULT_ACCOUNT_ADDR, &contract_name, (*DEFAULT_PAYMENT,))
    };
    InMemoryWasmTestBuilder::default()
        .run_genesis(&DEFAULT_GENESIS_CONFIG)
        .exec_with_exec_request(exec_request)
        .expect_success()
        .commit();
}

#[ignore]
#[test]
fn should_run_get_payment_purse_contract_account_1() {
    let exec_request_1 = {
        let contract_name = format!("{}.wasm", CONTRACT_TRANSFER_PURSE_TO_ACCOUNT);
        ExecuteRequestBuilder::standard(
            DEFAULT_ACCOUNT_ADDR,
            &contract_name,
            (ACCOUNT_1_ADDR, U512::from(ACCOUNT_1_INITIAL_BALANCE)),
        )
    };
    let exec_request_2 = {
        let contract_name = format!("{}.wasm", CONTRACT_POS_GET_PAYMENT_PURSE);
        ExecuteRequestBuilder::standard(ACCOUNT_1_ADDR, &contract_name, (*DEFAULT_PAYMENT,))
    };
    InMemoryWasmTestBuilder::default()
        .run_genesis(&DEFAULT_GENESIS_CONFIG)
        .exec_with_exec_request(exec_request_1)
        .expect_success()
        .commit()
        .exec_with_exec_request(exec_request_2)
        .expect_success()
        .commit();
}
