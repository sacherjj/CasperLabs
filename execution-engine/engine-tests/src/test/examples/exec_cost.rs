use types::U512;

use engine_test_support::{
    Code, SessionBuilder, TestContextBuilder, DEFAULT_ACCOUNT_ADDR, DEFAULT_ACCOUNT_INITIAL_BALANCE,
};

const DO_NOTHING_WASM: &str = "do_nothing.wasm";
const STANDARD_PAYMENT_WASM: &str = "standard_payment.wasm";
const PAYMENT_AMOUNT: u64 = 100_000_000;
const TRANSFER_WASM: &str = "transfer_main_purse_to_new_purse.wasm";
const NEW_PURSE_NAME: &str = "test_purse";
const TRANSFER_AMOUNT: u64 = 142;
const ZERO_U512: U512 = U512([0; 8]);

#[ignore]
#[test]
fn test_context_should_return_exec_cost_and_last_exec_cost() {
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
    assert_eq!(gas_cost_no_payment, ZERO_U512);

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
    assert!(gas_cost_with_payment > ZERO_U512);

    let account_balance = test_context.get_main_purse_balance(DEFAULT_ACCOUNT_ADDR);
    assert!(account_balance < U512::from(DEFAULT_ACCOUNT_INITIAL_BALANCE));

    // Using contract with session cost, and back to zero payment cost.
    // Doing a transfer to test actual used gas along with moving motes.
    let session = SessionBuilder::new(
        Code::from(TRANSFER_WASM),
        (NEW_PURSE_NAME, U512::from(TRANSFER_AMOUNT)),
    )
    .with_address(DEFAULT_ACCOUNT_ADDR)
    .with_authorization_keys(&[DEFAULT_ACCOUNT_ADDR])
    .build();
    test_context.run(session);

    let gas_cost_for_session = test_context.exec_cost(2);
    assert!(gas_cost_for_session > ZERO_U512);

    let new_account_balance = test_context.get_main_purse_balance(DEFAULT_ACCOUNT_ADDR);
    let expected_account_balance = account_balance - gas_cost_for_session - TRANSFER_AMOUNT;
    assert_eq!(new_account_balance, expected_account_balance);
}
