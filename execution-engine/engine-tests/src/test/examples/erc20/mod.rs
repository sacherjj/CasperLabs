mod erc20_test;

use engine_test_support::low_level::{DEFAULT_ACCOUNT_ADDR, DEFAULT_ACCOUNT_INITIAL_BALANCE};
use types::U512;

use erc20_test::ERC20Test;

const ACCOUNT_1: [u8; 32] = DEFAULT_ACCOUNT_ADDR;
const ACCOUNT_2: [u8; 32] = [2u8; 32];
const ACCOUNT_3: [u8; 32] = [3u8; 32];

#[ignore]
#[test]
fn test_erc20_deploy() {
    let initial_supply = U512::from(10);
    ERC20Test::new(ACCOUNT_1, initial_supply)
        .assert_erc20_balance(ACCOUNT_1, initial_supply)
        .assert_erc20_total_supply(initial_supply);
}

#[ignore]
#[test]
fn test_erc20_transfer() {
    let initial_supply = U512::from(10);
    ERC20Test::new(ACCOUNT_1, initial_supply)
        .call_erc20_transfer(ACCOUNT_1, ACCOUNT_2, initial_supply)
        .assert_success_status_and_commit()
        .assert_erc20_balance(ACCOUNT_1, U512::zero())
        .assert_erc20_balance(ACCOUNT_2, initial_supply)
        .assert_erc20_total_supply(initial_supply);
}

#[ignore]
#[test]
fn test_erc20_transfer_too_much() {
    let initial_supply = U512::from(10);
    let too_much = U512::from(20);
    ERC20Test::new(ACCOUNT_1, initial_supply)
        .call_erc20_transfer(ACCOUNT_1, ACCOUNT_2, too_much)
        .assert_failure_with_exit_code(65545);
}

#[ignore]
#[test]
fn test_erc20_balance() {
    let initial_supply = U512::from(10);
    ERC20Test::new(ACCOUNT_1, initial_supply)
        .call_erc20_balance_assertion(ACCOUNT_1, initial_supply)
        .assert_success_status_and_commit()
        .assert_erc20_balance(ACCOUNT_1, initial_supply);
}

#[ignore]
#[test]
fn test_erc20_balance_returns_error_code() {
    let initial_supply = U512::from(10);
    let other = U512::from(20);
    ERC20Test::new(ACCOUNT_1, initial_supply)
        .call_erc20_balance_assertion(ACCOUNT_1, other)
        .assert_failure_with_exit_code(65542);
}

#[ignore]
#[test]
fn test_erc20_allowance() {
    let initial_supply = U512::from(10);
    ERC20Test::new(ACCOUNT_1, initial_supply)
        .call_erc20_allowance_assertion(ACCOUNT_1, ACCOUNT_2, U512::zero())
        .assert_success_status_and_commit()
        .call_erc20_approve(ACCOUNT_1, ACCOUNT_2, initial_supply)
        .assert_success_status_and_commit()
        .call_erc20_allowance_assertion(ACCOUNT_1, ACCOUNT_2, initial_supply)
        .assert_success_status_and_commit()
        .assert_erc20_allowance(ACCOUNT_1, ACCOUNT_2, initial_supply);
}

#[ignore]
#[test]
fn test_erc20_allowance_returns_error_code() {
    let initial_supply = U512::from(10);
    ERC20Test::new(ACCOUNT_1, initial_supply)
        .call_erc20_allowance_assertion(ACCOUNT_1, ACCOUNT_2, initial_supply)
        .assert_failure_with_exit_code(65544);
}

#[ignore]
#[test]
fn test_erc20_total_supply() {
    let initial_supply = U512::from(10);
    ERC20Test::new(ACCOUNT_1, initial_supply)
        .call_erc20_total_supply_assertion(ACCOUNT_1, initial_supply)
        .assert_success_status_and_commit();
}

#[ignore]
#[test]
fn test_erc20_total_supply_returns_error_code() {
    let initial_supply = U512::from(10);
    ERC20Test::new(ACCOUNT_1, initial_supply)
        .call_erc20_total_supply_assertion(ACCOUNT_1, U512::zero())
        .assert_failure_with_exit_code(65543);
}

#[ignore]
#[test]
fn test_erc20_approve() {
    let initial_supply = U512::from(10);
    let allowance = U512::from(5);
    ERC20Test::new(ACCOUNT_1, initial_supply)
        .call_erc20_approve(ACCOUNT_1, ACCOUNT_2, allowance)
        .assert_success_status_and_commit()
        .assert_erc20_allowance(ACCOUNT_1, ACCOUNT_2, allowance);
}

#[ignore]
#[test]
fn test_erc20_transfer_from() {
    let initial_supply = U512::from(10);
    let allowance = U512::from(5);
    let transfered = U512::from(2);
    let clx_balance = U512::from(500_000_000);
    ERC20Test::new(ACCOUNT_1, initial_supply)
        .call_clx_transfer_with_success(ACCOUNT_1, ACCOUNT_2, clx_balance)
        .call_erc20_approve(ACCOUNT_1, ACCOUNT_2, allowance)
        .assert_success_status_and_commit()
        .call_erc20_transfer_from(ACCOUNT_2, ACCOUNT_1, ACCOUNT_3, transfered)
        .assert_success_status_and_commit()
        .assert_erc20_balance(ACCOUNT_1, initial_supply - transfered)
        .assert_erc20_balance(ACCOUNT_3, transfered)
        .assert_erc20_allowance(ACCOUNT_1, ACCOUNT_2, allowance - transfered);
}

#[ignore]
#[test]
fn test_erc20_transfer_from_more_than_allowance() {
    let initial_supply = U512::from(10);
    let allowance = U512::from(5);
    let too_much = U512::from(6);
    let clx_balance = U512::from(500_000_000);
    ERC20Test::new(ACCOUNT_1, initial_supply)
        .call_clx_transfer_with_success(ACCOUNT_1, ACCOUNT_2, clx_balance)
        .call_erc20_approve(ACCOUNT_1, ACCOUNT_2, allowance)
        .assert_success_status_and_commit()
        .call_erc20_transfer_from(ACCOUNT_2, ACCOUNT_1, ACCOUNT_3, too_much)
        .assert_failure_with_exit_code(65547);
}

#[ignore]
#[test]
fn test_erc20_transfer_from_too_much() {
    let initial_supply = U512::from(10);
    let allowance = U512::from(50);
    let too_much = U512::from(11);
    let clx_balance = U512::from(500_000_000);
    ERC20Test::new(ACCOUNT_1, initial_supply)
        .call_clx_transfer_with_success(ACCOUNT_1, ACCOUNT_2, clx_balance)
        .call_erc20_approve(ACCOUNT_1, ACCOUNT_2, allowance)
        .assert_success_status_and_commit()
        .call_erc20_transfer_from(ACCOUNT_2, ACCOUNT_1, ACCOUNT_3, too_much)
        .assert_failure_with_exit_code(65546);
}

#[ignore]
#[test]
fn test_erc20_buy() {
    let initial_supply = U512::from(10);
    let buy_amount = U512::from(5);
    ERC20Test::new(ACCOUNT_1, initial_supply)
        .call_erc20_buy(ACCOUNT_1, buy_amount)
        .assert_success_status_and_commit()
        .assert_erc20_balance(ACCOUNT_1, initial_supply + buy_amount)
        .assert_erc20_total_supply(initial_supply + buy_amount)
        .assert_clx_account_balance_no_gas(
            ACCOUNT_1,
            U512::from(DEFAULT_ACCOUNT_INITIAL_BALANCE) - buy_amount,
        )
        .assert_clx_contract_balance(buy_amount);
}

#[ignore]
#[test]
fn test_erc20_sell() {
    let initial_supply = U512::from(10);
    let buy_amount = U512::from(5);
    let sell_amount = U512::from(2);
    ERC20Test::new(ACCOUNT_1, initial_supply)
        .call_erc20_buy(ACCOUNT_1, buy_amount)
        .assert_success_status_and_commit()
        .call_erc20_sell(ACCOUNT_1, sell_amount)
        .assert_success_status_and_commit()
        .assert_erc20_balance(ACCOUNT_1, initial_supply + buy_amount - sell_amount)
        .assert_erc20_total_supply(initial_supply + buy_amount - sell_amount)
        .assert_clx_account_balance_no_gas(
            ACCOUNT_1,
            U512::from(DEFAULT_ACCOUNT_INITIAL_BALANCE) - buy_amount + sell_amount,
        )
        .assert_clx_contract_balance(buy_amount - sell_amount);
}

#[ignore]
#[test]
fn test_erc20_sell_too_much() {
    let initial_supply = U512::from(10);
    let too_much = U512::from(12);
    ERC20Test::new(ACCOUNT_1, initial_supply)
        .call_erc20_sell(ACCOUNT_1, too_much)
        .assert_failure_with_exit_code(65551);
}

#[ignore]
#[test]
fn test_erc20_sell_not_enough_clx_in_contract() {
    let initial_supply = U512::from(10);
    let too_much = U512::from(1);
    ERC20Test::new(ACCOUNT_1, initial_supply)
        .call_erc20_sell(ACCOUNT_1, too_much)
        .assert_failure_with_exit_code(65548);
}
