use engine_test_support::low_level::{
    ExecuteRequestBuilder, InMemoryWasmTestBuilder, DEFAULT_ACCOUNT_ADDR, DEFAULT_GENESIS_CONFIG,
    DEFAULT_PAYMENT,
};
use types::U512;

const CONTRACT_POS_GET_PAYMENT_PURSE: &str = "pos_get_payment_purse.wasm";
const CONTRACT_TRANSFER_PURSE_TO_ACCOUNT: &str = "transfer_purse_to_account.wasm";
const ACCOUNT_1_ADDR: [u8; 32] = [1u8; 32];
const ACCOUNT_1_INITIAL_BALANCE: u64 = 100_000_000 + 100;

#[ignore]
#[test]
fn should_run_get_payment_purse_contract_default_account() {
    let exec_request = ExecuteRequestBuilder::standard(
        DEFAULT_ACCOUNT_ADDR,
        CONTRACT_POS_GET_PAYMENT_PURSE,
        (*DEFAULT_PAYMENT,),
    )
    .build();
    InMemoryWasmTestBuilder::default()
        .run_genesis(&DEFAULT_GENESIS_CONFIG)
        .exec(exec_request)
        .expect_success()
        .commit();
}

#[ignore]
#[test]
fn should_run_get_payment_purse_contract_account_1() {
    let exec_request_1 = ExecuteRequestBuilder::standard(
        DEFAULT_ACCOUNT_ADDR,
        CONTRACT_TRANSFER_PURSE_TO_ACCOUNT,
        (ACCOUNT_1_ADDR, U512::from(ACCOUNT_1_INITIAL_BALANCE)),
    )
    .build();
    let exec_request_2 = ExecuteRequestBuilder::standard(
        ACCOUNT_1_ADDR,
        CONTRACT_POS_GET_PAYMENT_PURSE,
        (*DEFAULT_PAYMENT,),
    )
    .build();
    InMemoryWasmTestBuilder::default()
        .run_genesis(&DEFAULT_GENESIS_CONFIG)
        .exec(exec_request_1)
        .expect_success()
        .commit()
        .exec(exec_request_2)
        .expect_success()
        .commit();
}
