use engine_test_support::low_level::{
    ExecuteRequestBuilder, WasmTestBuilder, DEFAULT_ACCOUNT_ADDR, DEFAULT_GENESIS_CONFIG,
};
const CONTRACT_MINT_PURSE: &str = "mint_purse.wasm";
const CONTRACT_TRANSFER_TO_ACCOUNT_01: &str = "transfer_to_account_01.wasm";
const SYSTEM_ADDR: [u8; 32] = [0u8; 32];

#[ignore]
#[test]
fn should_run_mint_purse_contract() {
    let exec_request_1 = ExecuteRequestBuilder::standard(
        DEFAULT_ACCOUNT_ADDR,
        CONTRACT_TRANSFER_TO_ACCOUNT_01,
        (SYSTEM_ADDR,),
    )
    .build();
    let exec_request_2 =
        ExecuteRequestBuilder::standard(SYSTEM_ADDR, CONTRACT_MINT_PURSE, ()).build();

    WasmTestBuilder::default()
        .run_genesis(&DEFAULT_GENESIS_CONFIG)
        .exec(exec_request_1)
        .commit()
        .expect_success()
        .exec(exec_request_2)
        .commit()
        .expect_success();
}

#[ignore]
#[test]
fn should_not_allow_non_system_accounts_to_mint() {
    let exec_request =
        ExecuteRequestBuilder::standard(DEFAULT_ACCOUNT_ADDR, CONTRACT_MINT_PURSE, ()).build();

    assert!(WasmTestBuilder::default()
        .run_genesis(&DEFAULT_GENESIS_CONFIG)
        .exec(exec_request)
        .commit()
        .is_error());
}
