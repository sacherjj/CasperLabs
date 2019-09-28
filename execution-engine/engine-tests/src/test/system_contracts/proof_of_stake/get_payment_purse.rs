use contract_ffi::value::U512;
use engine_core::engine_state::MAX_PAYMENT;

use crate::support::test_support::{
    InMemoryWasmTestBuilder, DEFAULT_BLOCK_TIME, STANDARD_PAYMENT_CONTRACT,
};
use crate::test::{DEFAULT_ACCOUNT_ADDR, DEFAULT_GENESIS_CONFIG, DEFAULT_PAYMENT};

const ACCOUNT_1_ADDR: [u8; 32] = [1u8; 32];
const ACCOUNT_1_INITIAL_BALANCE: u64 = 100_000_000 + 100;

#[ignore]
#[test]
fn should_run_get_payment_purse_contract_default_account() {
    InMemoryWasmTestBuilder::default()
        .run_genesis(&DEFAULT_GENESIS_CONFIG)
        .exec_with_args(
            DEFAULT_ACCOUNT_ADDR,
            STANDARD_PAYMENT_CONTRACT,
            (U512::from(MAX_PAYMENT),),
            "pos_get_payment_purse.wasm",
            // Default funding amount for standard payment
            (U512::from(MAX_PAYMENT),),
            DEFAULT_BLOCK_TIME,
            [1u8; 32],
        )
        .expect_success()
        .commit();
}

#[ignore]
#[test]
fn should_run_get_payment_purse_contract_account_1() {
    InMemoryWasmTestBuilder::default()
        .run_genesis(&DEFAULT_GENESIS_CONFIG)
        .exec_with_args(
            DEFAULT_ACCOUNT_ADDR,
            STANDARD_PAYMENT_CONTRACT,
            (*DEFAULT_PAYMENT,),
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
            (*DEFAULT_PAYMENT,),
            "pos_get_payment_purse.wasm",
            (*DEFAULT_PAYMENT,),
            DEFAULT_BLOCK_TIME,
            [2u8; 32],
        )
        .expect_success()
        .commit();
}
