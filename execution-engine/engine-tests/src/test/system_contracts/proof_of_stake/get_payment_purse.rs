use std::collections::HashMap;

use crate::support::test_support::{WasmTestBuilder, DEFAULT_BLOCK_TIME};

const GENESIS_ADDR: [u8; 32] = [6u8; 32];
const ACCOUNT_1_ADDR: [u8; 32] = [1u8; 32];

#[ignore]
#[test]
fn should_run_get_payment_purse_contract_genesis_account() {
    WasmTestBuilder::default()
        .run_genesis(GENESIS_ADDR, HashMap::new())
        .exec(
            GENESIS_ADDR,
            "pos_get_payment_purse.wasm",
            DEFAULT_BLOCK_TIME,
            1,
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
            "transfer_to_account_01.wasm",
            DEFAULT_BLOCK_TIME,
            1,
            (ACCOUNT_1_ADDR,),
        )
        .expect_success()
        .commit()
        .exec(
            ACCOUNT_1_ADDR,
            "pos_get_payment_purse.wasm",
            DEFAULT_BLOCK_TIME,
            1,
        )
        .expect_success()
        .commit();
}
