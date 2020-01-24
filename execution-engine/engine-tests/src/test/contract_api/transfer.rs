use lazy_static::lazy_static;

use engine_core::engine_state::CONV_RATE;
use engine_shared::motes::Motes;
use engine_test_support::low_level::{
    utils, ExecuteRequestBuilder, InMemoryWasmTestBuilder, DEFAULT_ACCOUNT_ADDR,
    DEFAULT_ACCOUNT_INITIAL_BALANCE, DEFAULT_GENESIS_CONFIG, DEFAULT_PAYMENT,
};
use types::U512;

const CONTRACT_TRANSFER_PURSE_TO_ACCOUNT: &str = "transfer_purse_to_account.wasm";
const CONTRACT_TRANSFER_TO_ACCOUNT_01: &str = "transfer_to_account_01.wasm";
const CONTRACT_TRANSFER_TO_ACCOUNT_02: &str = "transfer_to_account_02.wasm";

lazy_static! {
    static ref TRANSFER_1_AMOUNT: U512 = U512::from(250_000_000) + 1000;
    static ref TRANSFER_2_AMOUNT: U512 = U512::from(750);
    static ref TRANSFER_2_AMOUNT_WITH_ADV: U512 = *DEFAULT_PAYMENT + *TRANSFER_2_AMOUNT;
    static ref TRANSFER_TOO_MUCH: U512 = U512::from(u64::max_value());
    static ref ACCOUNT_1_INITIAL_BALANCE: U512 = *DEFAULT_PAYMENT;
}

const ACCOUNT_1_ADDR: [u8; 32] = [1u8; 32];
const ACCOUNT_2_ADDR: [u8; 32] = [2u8; 32];

#[ignore]
#[test]
fn should_transfer_to_account() {
    let initial_genesis_amount: U512 = U512::from(DEFAULT_ACCOUNT_INITIAL_BALANCE);
    let transfer_amount: U512 = *TRANSFER_1_AMOUNT;

    // Run genesis
    let mut builder = InMemoryWasmTestBuilder::default();

    let builder = builder.run_genesis(&DEFAULT_GENESIS_CONFIG);

    let default_account = builder
        .get_account(DEFAULT_ACCOUNT_ADDR)
        .expect("should get account");

    let default_account_purse_id = default_account.purse_id();

    // Check genesis account balance
    let genesis_balance = builder.get_purse_balance(default_account_purse_id);

    assert_eq!(genesis_balance, initial_genesis_amount,);

    // Exec transfer contract

    let exec_request_1 = ExecuteRequestBuilder::standard(
        DEFAULT_ACCOUNT_ADDR,
        CONTRACT_TRANSFER_TO_ACCOUNT_01,
        (ACCOUNT_1_ADDR,),
    )
    .build();

    builder.exec(exec_request_1).expect_success().commit();

    let account = builder
        .get_account(ACCOUNT_1_ADDR)
        .expect("should get account");
    let account_purse_id = account.purse_id();

    // Check genesis account balance

    let genesis_balance = builder.get_purse_balance(default_account_purse_id);

    let gas_cost =
        Motes::from_gas(builder.exec_costs(0)[0], CONV_RATE).expect("should convert gas to motes");

    assert_eq!(
        genesis_balance,
        initial_genesis_amount - gas_cost.value() - transfer_amount
    );

    // Check account 1 balance

    let account_1_balance = builder.get_purse_balance(account_purse_id);

    assert_eq!(account_1_balance, transfer_amount,);
}

#[ignore]
#[test]
fn should_transfer_from_account_to_account() {
    let initial_genesis_amount: U512 = U512::from(DEFAULT_ACCOUNT_INITIAL_BALANCE);
    let transfer_1_amount: U512 = *TRANSFER_1_AMOUNT;
    let transfer_2_amount: U512 = *TRANSFER_2_AMOUNT;

    // Run genesis
    let mut builder = InMemoryWasmTestBuilder::default();

    let builder = builder.run_genesis(&DEFAULT_GENESIS_CONFIG);

    let default_account = builder
        .get_account(DEFAULT_ACCOUNT_ADDR)
        .expect("should get account");

    let default_account_purse_id = default_account.purse_id();

    // Check genesis account balance
    let genesis_balance = builder.get_purse_balance(default_account_purse_id);

    assert_eq!(genesis_balance, initial_genesis_amount,);

    // Exec transfer 1 contract

    let exec_request_1 = ExecuteRequestBuilder::standard(
        DEFAULT_ACCOUNT_ADDR,
        CONTRACT_TRANSFER_TO_ACCOUNT_01,
        (ACCOUNT_1_ADDR,),
    )
    .build();

    builder.exec(exec_request_1).expect_success().commit();

    let exec_1_response = builder
        .get_exec_response(0)
        .expect("should have exec response");

    let genesis_balance = builder.get_purse_balance(default_account_purse_id);

    let gas_cost = Motes::from_gas(utils::get_exec_costs(exec_1_response)[0], CONV_RATE)
        .expect("should convert");

    assert_eq!(
        genesis_balance,
        initial_genesis_amount - gas_cost.value() - transfer_1_amount
    );

    // Check account 1 balance
    let account_1 = builder
        .get_account(ACCOUNT_1_ADDR)
        .expect("should have account 1");
    let account_1_purse_id = account_1.purse_id();
    let account_1_balance = builder.get_purse_balance(account_1_purse_id);

    assert_eq!(account_1_balance, transfer_1_amount,);

    // Exec transfer 2 contract

    let exec_request_2 = ExecuteRequestBuilder::standard(
        ACCOUNT_1_ADDR,
        CONTRACT_TRANSFER_TO_ACCOUNT_02,
        (*TRANSFER_2_AMOUNT,),
    )
    .build();

    builder.exec(exec_request_2).expect_success().commit();

    let exec_2_response = builder
        .get_exec_response(1)
        .expect("should have exec response");

    let account_2 = builder
        .get_account(ACCOUNT_2_ADDR)
        .expect("should have account 2");

    let account_2_purse_id = account_2.purse_id();

    // Check account 1 balance

    let account_1_balance = builder.get_purse_balance(account_1_purse_id);

    let gas_cost = Motes::from_gas(utils::get_exec_costs(exec_2_response)[0], CONV_RATE)
        .expect("should convert");

    assert_eq!(
        account_1_balance,
        transfer_1_amount - gas_cost.value() - transfer_2_amount
    );

    let account_2_balance = builder.get_purse_balance(account_2_purse_id);

    assert_eq!(account_2_balance, transfer_2_amount,);
}

#[ignore]
#[test]
fn should_transfer_to_existing_account() {
    let initial_genesis_amount: U512 = U512::from(DEFAULT_ACCOUNT_INITIAL_BALANCE);
    let transfer_1_amount: U512 = *TRANSFER_1_AMOUNT;
    let transfer_2_amount: U512 = *TRANSFER_2_AMOUNT;

    // Run genesis
    let mut builder = InMemoryWasmTestBuilder::default();

    let builder = builder.run_genesis(&DEFAULT_GENESIS_CONFIG);

    let default_account = builder
        .get_account(DEFAULT_ACCOUNT_ADDR)
        .expect("should get account");

    let default_account_purse_id = default_account.purse_id();

    // Check genesis account balance
    let genesis_balance = builder.get_purse_balance(default_account_purse_id);

    assert_eq!(genesis_balance, initial_genesis_amount,);

    // Exec transfer 1 contract

    let exec_request_1 = ExecuteRequestBuilder::standard(
        DEFAULT_ACCOUNT_ADDR,
        CONTRACT_TRANSFER_TO_ACCOUNT_01,
        (ACCOUNT_1_ADDR,),
    )
    .build();

    builder.exec(exec_request_1).expect_success().commit();

    // Exec transfer contract

    let account_1 = builder
        .get_account(ACCOUNT_1_ADDR)
        .expect("should get account");

    let account_1_purse_id = account_1.purse_id();

    // Check genesis account balance

    let genesis_balance = builder.get_purse_balance(default_account_purse_id);

    let gas_cost =
        Motes::from_gas(builder.exec_costs(0)[0], CONV_RATE).expect("should convert gas to motes");

    assert_eq!(
        genesis_balance,
        initial_genesis_amount - gas_cost.value() - transfer_1_amount
    );

    // Check account 1 balance

    let account_1_balance = builder.get_purse_balance(account_1_purse_id);

    assert_eq!(account_1_balance, transfer_1_amount,);

    // Exec transfer contract

    let exec_request_2 = ExecuteRequestBuilder::standard(
        ACCOUNT_1_ADDR,
        CONTRACT_TRANSFER_TO_ACCOUNT_02,
        (*TRANSFER_2_AMOUNT,),
    )
    .build();
    builder.exec(exec_request_2).expect_success().commit();

    let account_2 = builder
        .get_account(ACCOUNT_2_ADDR)
        .expect("should get account");

    let account_2_purse_id = account_2.purse_id();

    // Check account 1 balance

    let account_1_balance = builder.get_purse_balance(account_1_purse_id);

    let gas_cost =
        Motes::from_gas(builder.exec_costs(1)[0], CONV_RATE).expect("should convert gas to motes");

    assert_eq!(
        account_1_balance,
        transfer_1_amount - gas_cost.value() - transfer_2_amount,
    );

    // Check account 2 balance

    let account_2_balance_transform = builder.get_purse_balance(account_2_purse_id);

    assert_eq!(account_2_balance_transform, transfer_2_amount);
}

#[ignore]
#[test]
fn should_fail_when_insufficient_funds() {
    // Run genesis

    let exec_request_1 = ExecuteRequestBuilder::standard(
        DEFAULT_ACCOUNT_ADDR,
        CONTRACT_TRANSFER_TO_ACCOUNT_01,
        (ACCOUNT_1_ADDR,),
    )
    .build();
    let exec_request_2 = ExecuteRequestBuilder::standard(
        ACCOUNT_1_ADDR,
        CONTRACT_TRANSFER_TO_ACCOUNT_02,
        (*TRANSFER_2_AMOUNT_WITH_ADV,),
    )
    .build();

    let exec_request_3 = ExecuteRequestBuilder::standard(
        ACCOUNT_1_ADDR,
        CONTRACT_TRANSFER_TO_ACCOUNT_02,
        (*TRANSFER_TOO_MUCH,),
    )
    .build();

    let result = InMemoryWasmTestBuilder::default()
        .run_genesis(&DEFAULT_GENESIS_CONFIG)
        // Exec transfer contract
        .exec(exec_request_1)
        .expect_success()
        .commit()
        // Exec transfer contract
        .exec(exec_request_2)
        .expect_success()
        .commit()
        // // Exec transfer contract
        .exec(exec_request_3)
        // .expect_success()
        .commit()
        .finish();

    assert!(result
        .builder()
        .exec_error_message(2)
        .expect("should have error message")
        .contains("Trap(Trap { kind: Unreachable })"))
}

#[ignore]
#[test]
fn should_transfer_total_amount() {
    let mut builder = InMemoryWasmTestBuilder::default();

    let exec_request_1 = ExecuteRequestBuilder::standard(
        DEFAULT_ACCOUNT_ADDR,
        CONTRACT_TRANSFER_PURSE_TO_ACCOUNT,
        (ACCOUNT_1_ADDR, *ACCOUNT_1_INITIAL_BALANCE),
    )
    .build();

    let exec_request_2 = ExecuteRequestBuilder::standard(
        ACCOUNT_1_ADDR,
        CONTRACT_TRANSFER_PURSE_TO_ACCOUNT,
        (ACCOUNT_2_ADDR, *ACCOUNT_1_INITIAL_BALANCE),
    )
    .build();
    builder
        .run_genesis(&DEFAULT_GENESIS_CONFIG)
        .exec(exec_request_1)
        .expect_success()
        .commit()
        .exec(exec_request_2)
        .commit()
        .expect_success()
        .finish();
}
