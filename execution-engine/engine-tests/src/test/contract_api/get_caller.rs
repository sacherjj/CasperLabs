use contract_ffi::value::account::PublicKey;

use crate::support::test_support::{ExecuteRequestBuilder, InMemoryWasmTestBuilder};
use crate::test::{DEFAULT_ACCOUNT_ADDR, DEFAULT_GENESIS_CONFIG, DEFAULT_PAYMENT};

const CONTRACT_GET_CALLER: &str = "get_caller";
const CONTRACT_GET_CALLER_SUBCALL: &str = "get_caller_subcall";
const CONTRACT_TRANSFER_PURSE_TO_ACCOUNT: &str = "transfer_purse_to_account";
const ACCOUNT_1_ADDR: [u8; 32] = [1u8; 32];

#[ignore]
#[test]
fn should_run_get_caller_contract() {
    let exec_request_1 = {
        let contract_name = format!("{}.wasm", CONTRACT_GET_CALLER);
        ExecuteRequestBuilder::standard(
            DEFAULT_ACCOUNT_ADDR,
            &contract_name,
            (PublicKey::new(DEFAULT_ACCOUNT_ADDR),),
        )
    };
    InMemoryWasmTestBuilder::default()
        .run_genesis(&DEFAULT_GENESIS_CONFIG)
        .exec_with_exec_request(exec_request_1)
        .commit()
        .expect_success();

    let exec_request_2 = {
        let contract_name = format!("{}.wasm", CONTRACT_TRANSFER_PURSE_TO_ACCOUNT);
        ExecuteRequestBuilder::standard(
            DEFAULT_ACCOUNT_ADDR,
            &contract_name,
            (ACCOUNT_1_ADDR, *DEFAULT_PAYMENT),
        )
    };
    let exec_request_3 = {
        let contract_name = format!("{}.wasm", CONTRACT_GET_CALLER);
        ExecuteRequestBuilder::standard(
            ACCOUNT_1_ADDR,
            &contract_name,
            (PublicKey::new(ACCOUNT_1_ADDR),),
        )
    };
    InMemoryWasmTestBuilder::default()
        .run_genesis(&DEFAULT_GENESIS_CONFIG)
        .exec_with_exec_request(exec_request_2)
        .commit()
        .expect_success()
        .exec_with_exec_request(exec_request_3)
        .commit()
        .expect_success();
}

#[ignore]
#[test]
fn should_run_get_caller_subcall_contract() {
    let exec_request_1 = {
        let contract_name = format!("{}.wasm", CONTRACT_GET_CALLER_SUBCALL);
        ExecuteRequestBuilder::standard(
            DEFAULT_ACCOUNT_ADDR,
            &contract_name,
            (PublicKey::new(DEFAULT_ACCOUNT_ADDR),),
        )
    };
    InMemoryWasmTestBuilder::default()
        .run_genesis(&DEFAULT_GENESIS_CONFIG)
        .exec_with_exec_request(exec_request_1)
        .commit()
        .expect_success();

    let exec_request_2 = {
        let contract_name = format!("{}.wasm", CONTRACT_TRANSFER_PURSE_TO_ACCOUNT);
        ExecuteRequestBuilder::standard(
            DEFAULT_ACCOUNT_ADDR,
            &contract_name,
            (ACCOUNT_1_ADDR, *DEFAULT_PAYMENT),
        )
    };
    let exec_request_3 = {
        let contract_name = format!("{}.wasm", CONTRACT_GET_CALLER_SUBCALL);
        ExecuteRequestBuilder::standard(
            ACCOUNT_1_ADDR,
            &contract_name,
            (PublicKey::new(ACCOUNT_1_ADDR),),
        )
    };
    InMemoryWasmTestBuilder::default()
        .run_genesis(&DEFAULT_GENESIS_CONFIG)
        .exec_with_exec_request(exec_request_2)
        .commit()
        .expect_success()
        .exec_with_exec_request(exec_request_3)
        .commit()
        .expect_success();
}
