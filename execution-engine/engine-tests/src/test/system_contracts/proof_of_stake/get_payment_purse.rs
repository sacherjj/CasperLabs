use contract_ffi::value::U512;
use engine_core::engine_state::MAX_PAYMENT;
use std::collections::HashMap;

use crate::support::test_support::{
    DEFAULT_BLOCK_TIME, STANDARD_PAYMENT_CONTRACT, WasmTestBuilder,
};

const GENESIS_ADDR: [u8; 32] = [6u8; 32];
const ACCOUNT_1_ADDR: [u8; 32] = [1u8; 32];
const ACCOUNT_1_INITIAL_BALANCE: u64 = MAX_PAYMENT + 100;

#[ignore]
#[test]
fn should_run_get_payment_purse_contract_genesis_account() {
    WasmTestBuilder::default()
        .run_genesis(GENESIS_ADDR, HashMap::new())
        .exec_with_args(
            GENESIS_ADDR,
            STANDARD_PAYMENT_CONTRACT,
            (U512::from(MAX_PAYMENT), ),
            "pos_get_payment_purse.wasm",
            // Default funding amount for standard payment
            (U512::from(MAX_PAYMENT), ),
            DEFAULT_BLOCK_TIME,
            [1u8; 32],
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
            STANDARD_PAYMENT_CONTRACT,
            (U512::from(MAX_PAYMENT), ),
            "transfer_purse_to_account.wasm",
            (ACCOUNT_1_ADDR, U512::from(ACCOUNT_1_INITIAL_BALANCE)),
            DEFAULT_BLOCK_TIME,
            [1; 32],
        )
        .expect_success()
        .commit()
        .exec_with_args(
            ACCOUNT_1_ADDR,
            STANDARD_PAYMENT_CONTRACT,
            (U512::from(MAX_PAYMENT), ),
            "pos_get_payment_purse.wasm",
            (U512::from(MAX_PAYMENT), ),
            DEFAULT_BLOCK_TIME,
            [2u8; 32],
        )
        .expect_success()
        .commit();
}
