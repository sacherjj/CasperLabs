use std::convert::TryFrom;

use rand::Rng;
use types::{CLValue, Key, U512};

use engine_shared::gas::Gas;

// use engine_test_support::{
// internal::{
//     DeployItemBuilder, ExecuteRequestBuilder, InMemoryWasmTestBuilder,
//     DEFAULT_RUN_GENESIS_REQUEST,
// },};
use engine_test_support::{
    Code, Session, SessionBuilder, TestContext, TestContextBuilder, DEFAULT_ACCOUNT_ADDR,
    DEFAULT_ACCOUNT_INITIAL_BALANCE,
};

const DO_NOTHING_WASM: &str = "do_nothing.wasm";
const CREATE_PURSE_01_WASM: &str = "create_purse_01.wasm";
const TEST_PURSE_NAME: &str = "test_purse";
const STANDARD_PAYMENT_WASM: &str = "standard_payment.wasm";
const PAYMENT_AMOUNT: u64 = 100_000_000;

#[ignore]
#[test]
fn test_context_should_return_exec_cost_and_last_exec_cost() {
    let u512_zero = U512::from(0u64);

    let mut test_context = TestContextBuilder::new()
        .with_account(
            DEFAULT_ACCOUNT_ADDR,
            U512::from(DEFAULT_ACCOUNT_INITIAL_BALANCE),
        )
        .build();

    // Payment is not charged by default, so do nothing should execute with
    // zero payment and session costs.
    let session = SessionBuilder::new(Code::from(DO_NOTHING_WASM), ())
        .with_address(DEFAULT_ACCOUNT_ADDR)
        .with_authorization_keys(&[DEFAULT_ACCOUNT_ADDR])
        .build();
    test_context.run(session);

    let gas_cost_no_payment = test_context.exec_cost(0);
    assert_eq!(gas_cost_no_payment, u512_zero);

    let account_balance = test_context.get_main_purse_balance(DEFAULT_ACCOUNT_ADDR);
    assert_eq!(account_balance, U512::from(DEFAULT_ACCOUNT_INITIAL_BALANCE));

    // Passing in standard_payment will execute and charge for payment code, but session cost
    // is still zero with do_nothing.
    let session = SessionBuilder::new(Code::from(DO_NOTHING_WASM), ())
        .with_address(DEFAULT_ACCOUNT_ADDR)
        .with_authorization_keys(&[DEFAULT_ACCOUNT_ADDR])
        .with_payment_code(
            Code::from(STANDARD_PAYMENT_WASM),
            (U512::from(PAYMENT_AMOUNT),),
        )
        .build();
    test_context.run(session);

    // Testing get_last_exec_cost, could also be exec_cost(1)
    let gas_cost_with_payment = test_context.get_last_exec_cost();
    assert!(gas_cost_with_payment > u512_zero);

    let account_balance = test_context.get_main_purse_balance(DEFAULT_ACCOUNT_ADDR);
    assert!(account_balance < U512::from(DEFAULT_ACCOUNT_INITIAL_BALANCE));

    // Using contract with session cost, and back to zero payment cost.
    // Not using purse, just need session contract with operations.
    let session = SessionBuilder::new(Code::from(CREATE_PURSE_01_WASM), (TEST_PURSE_NAME,))
        .with_address(DEFAULT_ACCOUNT_ADDR)
        .with_authorization_keys(&[DEFAULT_ACCOUNT_ADDR])
        .build();
    test_context.run(session);
    let gas_cost_for_session = test_context.exec_cost(2);
    assert!(gas_cost_for_session > u512_zero);

    let new_account_balance = test_context.get_main_purse_balance(DEFAULT_ACCOUNT_ADDR);
    assert!(new_account_balance < account_balance);
}
