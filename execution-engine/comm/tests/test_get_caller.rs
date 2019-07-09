extern crate casperlabs_engine_grpc_server;
extern crate common;
extern crate execution_engine;
extern crate grpc;
extern crate shared;
extern crate storage;

use std::collections::HashMap;

use common::value::account::PublicKey;
use test_support::{WasmTestBuilder, DEFAULT_BLOCK_TIME};

#[allow(dead_code)]
mod test_support;

const GENESIS_ADDR: [u8; 32] = [7u8; 32];
const ACCOUNT_1_ADDR: [u8; 32] = [1u8; 32];

#[ignore]
#[test]
fn should_run_get_caller_contract() {
    WasmTestBuilder::default()
        .run_genesis(GENESIS_ADDR, HashMap::new())
        .exec_with_args(
            GENESIS_ADDR,
            "get_caller.wasm",
            DEFAULT_BLOCK_TIME,
            1,
            PublicKey::new(GENESIS_ADDR),
        )
        .commit()
        .expect_success();

    WasmTestBuilder::default()
        .run_genesis(GENESIS_ADDR, HashMap::new())
        .exec_with_args(
            GENESIS_ADDR,
            "transfer_to_account_01.wasm",
            DEFAULT_BLOCK_TIME,
            1,
            ACCOUNT_1_ADDR,
        )
        .commit()
        .expect_success()
        .exec_with_args(
            ACCOUNT_1_ADDR,
            "get_caller.wasm",
            DEFAULT_BLOCK_TIME,
            1,
            PublicKey::new(ACCOUNT_1_ADDR),
        )
        .commit()
        .expect_success();
}

#[ignore]
#[test]
fn should_run_get_caller_subcall_contract() {
    WasmTestBuilder::default()
        .run_genesis(GENESIS_ADDR, HashMap::new())
        .exec_with_args(
            GENESIS_ADDR,
            "get_caller_subcall.wasm",
            DEFAULT_BLOCK_TIME,
            1,
            PublicKey::new(GENESIS_ADDR),
        )
        .commit()
        .expect_success();

    WasmTestBuilder::default()
        .run_genesis(GENESIS_ADDR, HashMap::new())
        .exec_with_args(
            GENESIS_ADDR,
            "transfer_to_account_01.wasm",
            DEFAULT_BLOCK_TIME,
            1,
            ACCOUNT_1_ADDR,
        )
        .commit()
        .expect_success()
        .exec_with_args(
            ACCOUNT_1_ADDR,
            "get_caller_subcall.wasm",
            DEFAULT_BLOCK_TIME,
            1,
            PublicKey::new(ACCOUNT_1_ADDR),
        )
        .commit()
        .expect_success();
}
