use engine_test_support::{
    internal::{
        ExecuteRequestBuilder, InMemoryWasmTestBuilder, DEFAULT_PAYMENT,
        DEFAULT_RUN_GENESIS_REQUEST,
    },
    DEFAULT_ACCOUNT_ADDR,
};
use types::account::PublicKey;

const CONTRACT_GET_CALLER: &str = "get_caller.wasm";
const CONTRACT_GET_CALLER_SUBCALL: &str = "get_caller_subcall.wasm";
const CONTRACT_TRANSFER_PURSE_TO_ACCOUNT: &str = "transfer_purse_to_account.wasm";
const ACCOUNT_1_ADDR: PublicKey = PublicKey::ed25519_from([1u8; 32]);

#[ignore]
#[test]
fn should_run_get_caller_contract() {
    let exec_request_1 = ExecuteRequestBuilder::standard(
        DEFAULT_ACCOUNT_ADDR,
        CONTRACT_GET_CALLER,
        (DEFAULT_ACCOUNT_ADDR,),
    )
    .build();
    InMemoryWasmTestBuilder::default()
        .run_genesis(&DEFAULT_RUN_GENESIS_REQUEST)
        .exec(exec_request_1)
        .commit()
        .expect_success();

    let exec_request_2 = ExecuteRequestBuilder::standard(
        DEFAULT_ACCOUNT_ADDR,
        CONTRACT_TRANSFER_PURSE_TO_ACCOUNT,
        (ACCOUNT_1_ADDR, *DEFAULT_PAYMENT),
    )
    .build();
    let exec_request_3 =
        ExecuteRequestBuilder::standard(ACCOUNT_1_ADDR, CONTRACT_GET_CALLER, (ACCOUNT_1_ADDR,))
            .build();
    InMemoryWasmTestBuilder::default()
        .run_genesis(&DEFAULT_RUN_GENESIS_REQUEST)
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
        (DEFAULT_ACCOUNT_ADDR,),
    )
    .build();
    InMemoryWasmTestBuilder::default()
        .run_genesis(&DEFAULT_RUN_GENESIS_REQUEST)
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
        (ACCOUNT_1_ADDR,),
    )
    .build();
    InMemoryWasmTestBuilder::default()
        .run_genesis(&DEFAULT_RUN_GENESIS_REQUEST)
        .exec(exec_request_2)
        .commit()
        .expect_success()
        .exec(exec_request_3)
        .commit()
        .expect_success();
}
