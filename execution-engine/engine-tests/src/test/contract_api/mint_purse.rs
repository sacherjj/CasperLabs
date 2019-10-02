use crate::support::test_support::{ExecuteRequestBuilder, WasmTestBuilder};
use crate::test::{DEFAULT_ACCOUNT_ADDR, DEFAULT_GENESIS_CONFIG};

const CONTRACT_MINT_PURSE: &str = "mint_purse";
const CONTRACT_TRANSFER_TO_ACCOUNT_01: &str = "transfer_to_account_01";
const SYSTEM_ADDR: [u8; 32] = [0u8; 32];

#[ignore]
#[test]
fn should_run_mint_purse_contract() {
    let exec_request_1 = {
        let contract_name = format!("{}.wasm", CONTRACT_TRANSFER_TO_ACCOUNT_01);
        ExecuteRequestBuilder::standard(DEFAULT_ACCOUNT_ADDR, &contract_name, (SYSTEM_ADDR,))
    };
    let exec_request_2 = {
        let contract_name = format!("{}.wasm", CONTRACT_MINT_PURSE);
        ExecuteRequestBuilder::standard(SYSTEM_ADDR, &contract_name, ())
    };

    WasmTestBuilder::default()
        .run_genesis(&DEFAULT_GENESIS_CONFIG)
        .exec_with_exec_request(exec_request_1)
        .commit()
        .expect_success()
        .exec_with_exec_request(exec_request_2)
        .commit()
        .expect_success();
}

#[ignore]
#[test]
fn should_not_allow_non_system_accounts_to_mint() {
    let exec_request = {
        let contract_name = format!("{}.wasm", CONTRACT_MINT_PURSE);
        ExecuteRequestBuilder::standard(DEFAULT_ACCOUNT_ADDR, &contract_name, ())
    };

    assert!(WasmTestBuilder::default()
        .run_genesis(&DEFAULT_GENESIS_CONFIG)
        .exec_with_exec_request(exec_request)
        .commit()
        .is_error());
}
