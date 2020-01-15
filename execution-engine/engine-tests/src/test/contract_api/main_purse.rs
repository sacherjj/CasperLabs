use engine_shared::stored_value::StoredValue;
use engine_test_support::low_level::{
    ExecuteRequestBuilder, InMemoryWasmTestBuilder, DEFAULT_ACCOUNT_ADDR, DEFAULT_GENESIS_CONFIG,
    DEFAULT_PAYMENT,
};
use types::Key;

const CONTRACT_MAIN_PURSE: &str = "main_purse.wasm";
const CONTRACT_TRANSFER_PURSE_TO_ACCOUNT: &str = "transfer_purse_to_account.wasm";
const ACCOUNT_1_ADDR: [u8; 32] = [1u8; 32];

#[ignore]
#[test]
fn should_run_main_purse_contract_default_account() {
    let mut builder = InMemoryWasmTestBuilder::default();

    let builder = builder.run_genesis(&DEFAULT_GENESIS_CONFIG);

    let default_account = if let Some(StoredValue::Account(account)) =
        builder.query(None, Key::Account(DEFAULT_ACCOUNT_ADDR), &[])
    {
        account
    } else {
        panic!("could not get account")
    };

    let exec_request = ExecuteRequestBuilder::standard(
        DEFAULT_ACCOUNT_ADDR,
        CONTRACT_MAIN_PURSE,
        (default_account.purse_id(),),
    )
    .build();

    builder.exec(exec_request).expect_success().commit();
}

#[ignore]
#[test]
fn should_run_main_purse_contract_account_1() {
    let mut builder = InMemoryWasmTestBuilder::default();

    let exec_request_1 = ExecuteRequestBuilder::standard(
        DEFAULT_ACCOUNT_ADDR,
        CONTRACT_TRANSFER_PURSE_TO_ACCOUNT,
        (ACCOUNT_1_ADDR, *DEFAULT_PAYMENT),
    )
    .build();

    let builder = builder
        .run_genesis(&DEFAULT_GENESIS_CONFIG)
        .exec(exec_request_1)
        .expect_success()
        .commit();

    let account_1 = builder
        .get_account(ACCOUNT_1_ADDR)
        .expect("should get account");

    let exec_request_2 = ExecuteRequestBuilder::standard(
        ACCOUNT_1_ADDR,
        CONTRACT_MAIN_PURSE,
        (account_1.purse_id(),),
    )
    .build();

    builder.exec(exec_request_2).expect_success().commit();
}
