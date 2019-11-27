extern crate alloc;

use contract_ffi::value::U512;

use crate::{
    support::test_support::InMemoryWasmTestBuilder as TestBuilder,
    test::{DEFAULT_ACCOUNT_ADDR, DEFAULT_GENESIS_CONFIG},
};

mod utils;

use utils::{
    approve, assert_allowance, assert_balance, assert_total_supply, deploy_erc20, transfer,
    transfer_from, transfer_motes,
};

const ACCOUNT_1: [u8; 32] = DEFAULT_ACCOUNT_ADDR;
const ACCOUNT_2: [u8; 32] = [2u8; 32];
const ACCOUNT_3: [u8; 32] = [3u8; 32];

#[ignore]
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
    assert_total_supply(
        &mut builder,
        proxy_hash,
        token_hash,
        ACCOUNT_1,
        init_balance,
    );
    assert_balance(
        &mut builder,
        proxy_hash,
        token_hash,
        ACCOUNT_1,
        ACCOUNT_1,
        init_balance,
    );
    assert_balance(
        &mut builder,
        proxy_hash,
        token_hash,
        ACCOUNT_1,
        ACCOUNT_2,
        U512::from(0),
    );

    // Transfer 20 tokens from ACCOUNT_1 to ACCOUNT_2.
    // Balance of ACCOUNT_1 should be 9980.
    // Balance of ACCOUNT_2 should be 20.
    let send_amount = U512::from(20);
    transfer(
        &mut builder,
        proxy_hash,
        token_hash,
        ACCOUNT_1,
        ACCOUNT_2,
        send_amount,
    );
    assert_total_supply(
        &mut builder,
        proxy_hash,
        token_hash,
        ACCOUNT_1,
        init_balance,
    );
    assert_balance(
        &mut builder,
        proxy_hash,
        token_hash,
        ACCOUNT_1,
        ACCOUNT_1,
        init_balance - send_amount,
    );
    assert_balance(
        &mut builder,
        proxy_hash,
        token_hash,
        ACCOUNT_1,
        ACCOUNT_2,
        send_amount,
    );

    // Transfer 15 tokens back from ACCOUNT_2 to ACCOUNT_1.
    // Balance of ACCOUNT_1 should be 9995.
    // Balance of ACCOUNT_2 should be 5.
    let pay_back = U512::from(15);
    transfer(
        &mut builder,
        proxy_hash,
        token_hash,
        ACCOUNT_2,
        ACCOUNT_1,
        pay_back,
    );
    assert_total_supply(
        &mut builder,
        proxy_hash,
        token_hash,
        ACCOUNT_1,
        init_balance,
    );
    assert_balance(
        &mut builder,
        proxy_hash,
        token_hash,
        ACCOUNT_1,
        ACCOUNT_1,
        init_balance - send_amount + pay_back,
    );
    assert_balance(
        &mut builder,
        proxy_hash,
        token_hash,
        ACCOUNT_1,
        ACCOUNT_2,
        send_amount - pay_back,
    );
}

#[ignore]
#[test]
fn test_erc20_approval_and_transfer_from() {
    // Init virtual machine.
    let mut builder = TestBuilder::default();
    builder.run_genesis(&DEFAULT_GENESIS_CONFIG).commit();
    transfer_motes(&mut builder, ACCOUNT_1, ACCOUNT_2, 100_000_000);

    // Deploy token.
    let init_balance = U512::from(1000);
    let (token_hash, proxy_hash) = deploy_erc20(&mut builder, ACCOUNT_1, "token", init_balance);

    // At start allowance should be 0 tokens.
    assert_allowance(
        &mut builder,
        proxy_hash,
        token_hash,
        ACCOUNT_1,
        ACCOUNT_1,
        ACCOUNT_2,
        U512::from(0),
    );

    // ACCOUNT_1 allows ACCOUNT_2 to spend 10 tokens.
    let allowance = U512::from(10);
    approve(
        &mut builder,
        proxy_hash,
        token_hash,
        ACCOUNT_1,
        ACCOUNT_2,
        allowance,
    );
    assert_allowance(
        &mut builder,
        proxy_hash,
        token_hash,
        ACCOUNT_1,
        ACCOUNT_1,
        ACCOUNT_2,
        allowance,
    );

    // ACCOUNT_2 sends 3 tokens to ACCOUNT_3 from ACCOUNT_1.
    // Balance of ACCOUNT_1 should be 9997.
    // Balance of ACCOUNT_2 should be 0.
    // Balance of ACCOUNT_3 should be 3.
    // Allowance of ACCOUNT_2 for ACCOUNT_1 should be 7.
    let send_amount = U512::from(3);
    transfer_from(
        &mut builder,
        proxy_hash,
        token_hash,
        ACCOUNT_2,
        ACCOUNT_1,
        ACCOUNT_3,
        send_amount,
    );
    assert_allowance(
        &mut builder,
        proxy_hash,
        token_hash,
        ACCOUNT_1,
        ACCOUNT_1,
        ACCOUNT_2,
        allowance - send_amount,
    );
    assert_balance(
        &mut builder,
        proxy_hash,
        token_hash,
        ACCOUNT_1,
        ACCOUNT_1,
        init_balance - send_amount,
    );
    assert_balance(
        &mut builder,
        proxy_hash,
        token_hash,
        ACCOUNT_1,
        ACCOUNT_2,
        U512::from(0),
    );
    assert_balance(
        &mut builder,
        proxy_hash,
        token_hash,
        ACCOUNT_1,
        ACCOUNT_3,
        send_amount,
    );
}
