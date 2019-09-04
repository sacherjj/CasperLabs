use std::collections::HashMap;

use crate::support::test_support::{PaymentCode, WasmTestBuilder, DEFAULT_BLOCK_TIME};
use contract_ffi::value::U512;
use engine_core::engine_state::MAX_PAYMENT;

const GENESIS_ADDR: [u8; 32] = [6u8; 32];
const ACCOUNT_1_ADDR: [u8; 32] = [1u8; 32];
const ACCOUNT_1_PAYMENT_FUND: u64 = MAX_PAYMENT - 1;
const ACCOUNT_1_INITIAL_BALANCE: u64 = ACCOUNT_1_PAYMENT_FUND + 100;

#[ignore]
#[test]
fn should_run_get_payment_purse_contract_genesis_account() {
    WasmTestBuilder::default()
        .run_genesis(GENESIS_ADDR, HashMap::new())
        .exec_with_args(
            GENESIS_ADDR,
            "pos_get_payment_purse.wasm",
            DEFAULT_BLOCK_TIME,
            1,
            // Default funding amount for standard payment
            (U512::from(MAX_PAYMENT),),
        )
        .expect_success()
        .commit();
}

#[ignore]
#[test]
fn should_run_get_payment_purse_contract_account_1() {
    WasmTestBuilder::default()
        .run_genesis(GENESIS_ADDR, HashMap::new())
        .exec_with_args(
            GENESIS_ADDR,
            "transfer_purse_to_account.wasm",
            DEFAULT_BLOCK_TIME,
            1,
            (ACCOUNT_1_ADDR, U512::from(ACCOUNT_1_INITIAL_BALANCE)),
        )
        .expect_success()
        .commit()
        .use_payment_code(PaymentCode::standard(U512::from(ACCOUNT_1_PAYMENT_FUND)))
        .exec_with_args(
            ACCOUNT_1_ADDR,
            "pos_get_payment_purse.wasm",
            DEFAULT_BLOCK_TIME,
            1,
            (U512::from(ACCOUNT_1_PAYMENT_FUND),),
        )
        .expect_success()
        .commit();
}
