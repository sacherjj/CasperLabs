use engine_test_support::low_level::{
    ExecuteRequestBuilder, InMemoryWasmTestBuilder, DEFAULT_ACCOUNT_ADDR, DEFAULT_GENESIS_CONFIG,
    DEFAULT_PAYMENT,
};
use types::account::PublicKey;

const CONTRACT_GET_CALLER: &str = "get_caller.wasm";
const CONTRACT_GET_CALLER_SUBCALL: &str = "get_caller_subcall.wasm";
const CONTRACT_TRANSFER_PURSE_TO_ACCOUNT: &str = "transfer_purse_to_account.wasm";
const ACCOUNT_1_ADDR: [u8; 32] = [1u8; 32];

#[ignore]
#[test]
fn should_run_get_caller_contract() {
    let exec_request_1 = ExecuteRequestBuilder::standard(
        DEFAULT_ACCOUNT_ADDR,
        CONTRACT_GET_CALLER,
        (PublicKey::new(DEFAULT_ACCOUNT_ADDR),),
    )
    .build();
    InMemoryWasmTestBuilder::default()
        .run_genesis(&DEFAULT_GENESIS_CONFIG)
        .exec(exec_request_1)
        .commit()
        .expect_success();

    let exec_request_2 = ExecuteRequestBuilder::standard(
        DEFAULT_ACCOUNT_ADDR,
        CONTRACT_TRANSFER_PURSE_TO_ACCOUNT,
        (ACCOUNT_1_ADDR, *DEFAULT_PAYMENT),
    )
    .build();
    let exec_request_3 = ExecuteRequestBuilder::standard(
        ACCOUNT_1_ADDR,
        CONTRACT_GET_CALLER,
        (PublicKey::new(ACCOUNT_1_ADDR),),
    )
    .build();
    InMemoryWasmTestBuilder::default()
        .run_genesis(&DEFAULT_GENESIS_CONFIG)
        .exec(exec_request_2)
        .commit()
        .expect_success()
        .exec(exec_request_3)
        .commit()
        .expect_success();
}

#[ignore]
#[test]
fn should_run_get_caller_subcall_contract() {
    let exec_request_1 = ExecuteRequestBuilder::standard(
        DEFAULT_ACCOUNT_ADDR,
        CONTRACT_GET_CALLER_SUBCALL,
        (PublicKey::new(DEFAULT_ACCOUNT_ADDR),),
    )
    .build();
    InMemoryWasmTestBuilder::default()
        .run_genesis(&DEFAULT_GENESIS_CONFIG)
        .exec(exec_request_1)
        .commit()
        .expect_success();

    let exec_request_2 = ExecuteRequestBuilder::standard(
        DEFAULT_ACCOUNT_ADDR,
        CONTRACT_TRANSFER_PURSE_TO_ACCOUNT,
        (ACCOUNT_1_ADDR, *DEFAULT_PAYMENT),
    )
    .build();
    let exec_request_3 = ExecuteRequestBuilder::standard(
        ACCOUNT_1_ADDR,
        CONTRACT_GET_CALLER_SUBCALL,
        (PublicKey::new(ACCOUNT_1_ADDR),),
    )
    .build();
    InMemoryWasmTestBuilder::default()
        .run_genesis(&DEFAULT_GENESIS_CONFIG)
        .exec(exec_request_2)
        .commit()
        .expect_success()
        .exec(exec_request_3)
        .commit()
        .expect_success();
}
