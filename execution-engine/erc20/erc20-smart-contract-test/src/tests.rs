extern crate engine_tests;
extern crate alloc;

use contract_ffi::value::U512;

use engine_tests::test::DEFAULT_ACCOUNT_ADDR;
use engine_tests::test::DEFAULT_GENESIS_CONFIG;
use engine_tests::support::test_support::InMemoryWasmTestBuilder as TestBuilder;

use crate::utils::assert_balance;
use crate::utils::assert_total_supply;
use crate::utils::deploy_erc20;
use crate::utils::transfer;
use crate::utils::transfer_motes;
use crate::utils::expect_transfer_error;

const ACCOUNT_1: [u8; 32] = DEFAULT_ACCOUNT_ADDR;
const ACCOUNT_2: [u8; 32] = [3u8; 32];

#[test]
fn test_erc20_transfer() {
    // Init virtual machine.
    let mut builder = TestBuilder::default();
    builder.run_genesis(&DEFAULT_GENESIS_CONFIG).commit();
    transfer_motes(&mut builder, ACCOUNT_1, ACCOUNT_2, 200_000_000);

    // Deploy token.
    // ACCOUNT_1 should have 1000 at start.
    // ACCOUNT_2 should have 0 balance.
    let init_balance = U512::from(1000);
    let (token_hash, proxy_hash) = deploy_erc20(&mut builder, ACCOUNT_1, "token", init_balance);
    // assert_total_supply(&mut builder, proxy_hash, token_hash, ACCOUNT_1, init_balance);
    assert_balance(&mut builder, proxy_hash, token_hash, ACCOUNT_1, ACCOUNT_1, init_balance);
    assert_balance(&mut builder, proxy_hash, token_hash, ACCOUNT_1, ACCOUNT_2, U512::from(0));

    // Transfer 20 tokens from ACCOUNT_1 to ACCOUNT_2.
    // Balance of ACCOUNT_1 should be 9980.
    // Balance of ACCOUNT_2 should be 20.
    let send_amount = U512::from(20);
    transfer(&mut builder, proxy_hash, token_hash, ACCOUNT_1, ACCOUNT_2, send_amount);
    assert_total_supply(&mut builder, proxy_hash, token_hash, ACCOUNT_1, init_balance);
    assert_balance(&mut builder, proxy_hash, token_hash, ACCOUNT_1, ACCOUNT_1, init_balance - send_amount);
    assert_balance(&mut builder, proxy_hash, token_hash, ACCOUNT_1, ACCOUNT_2, send_amount);

    // Transfer 15 tokens back from ACCOUNT_2 to ACCOUNT_1.
    // Balance of ACCOUNT_1 should be 9995.
    // Balance of ACCOUNT_2 should be 5.
    let pay_back = U512::from(15);
    transfer(&mut builder, proxy_hash, token_hash, ACCOUNT_2, ACCOUNT_1, pay_back);
    assert_total_supply(&mut builder, proxy_hash, token_hash, ACCOUNT_1, init_balance);
    assert_balance(&mut builder, proxy_hash, token_hash, ACCOUNT_1, ACCOUNT_1, init_balance - send_amount + pay_back);
    assert_balance(&mut builder, proxy_hash, token_hash, ACCOUNT_1, ACCOUNT_2, send_amount - pay_back);
}

#[test]
fn test_erc20_transfer_too_much() {
    // Init virtual machine.
    let mut builder = TestBuilder::default();
    builder.run_genesis(&DEFAULT_GENESIS_CONFIG).commit();
    transfer_motes(&mut builder, ACCOUNT_1, ACCOUNT_2, 100_000_000);

    // Deploy token.
    let (token_hash, proxy_hash) = deploy_erc20(&mut builder, ACCOUNT_1, "token", U512::from(1000));

    // Transfer too much.
    expect_transfer_error(&mut builder, proxy_hash, token_hash, ACCOUNT_1, ACCOUNT_2, U512::from(3000));
}