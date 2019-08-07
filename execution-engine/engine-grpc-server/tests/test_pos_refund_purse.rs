extern crate casperlabs_engine_grpc_server;
extern crate contract_ffi;
extern crate engine_core;
extern crate engine_shared;
extern crate engine_storage;
extern crate grpc;

use std::collections::HashMap;

use test_support::{WasmTestBuilder, DEFAULT_BLOCK_TIME};

#[allow(unused)]
mod test_support;

const GENESIS_ADDR: [u8; 32] = [6u8; 32];
const ACCOUNT_1_ADDR: [u8; 32] = [1u8; 32];

#[ignore]
#[test]
fn should_run_pos_refund_purse_contract_genesis_account() {
    WasmTestBuilder::default()
        .run_genesis(GENESIS_ADDR, HashMap::new())
        .exec(GENESIS_ADDR, "pos_refund_purse.wasm", DEFAULT_BLOCK_TIME, 1)
        .expect_success()
        .commit();
}

#[ignore]
#[test]
fn should_run_pos_refund_purse_contract_account_1() {
    WasmTestBuilder::default()
        .run_genesis(GENESIS_ADDR, HashMap::new())
        .exec_with_args(
            GENESIS_ADDR,
            "transfer_to_account_01.wasm",
            DEFAULT_BLOCK_TIME,
            1,
            ACCOUNT_1_ADDR,
        )
        .expect_success()
        .commit()
        .exec(
            ACCOUNT_1_ADDR,
            "pos_refund_purse.wasm",
            DEFAULT_BLOCK_TIME,
            1,
        )
        .expect_success()
        .commit();
}
