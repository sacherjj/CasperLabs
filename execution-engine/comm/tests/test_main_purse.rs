extern crate casperlabs_engine_grpc_server;
extern crate common;
extern crate execution_engine;
extern crate grpc;
extern crate shared;
extern crate storage;

use std::collections::HashMap;

use common::key::Key;
use common::value::Account;
use test_support::{WasmTestBuilder, DEFAULT_BLOCK_TIME};

#[allow(unused)]
mod test_support;

const GENESIS_ADDR: [u8; 32] = [6u8; 32];
const ACCOUNT_1_ADDR: [u8; 32] = [1u8; 32];

#[ignore]
#[test]
fn should_run_main_purse_contract_genesis_account() {
    let mut builder = WasmTestBuilder::default();

    let builder = builder.run_genesis(GENESIS_ADDR, HashMap::new());

    let genesis_account: Account = {
        let tmp = builder.clone();
        tmp.get_genesis_account().to_owned()
    };

    builder
        .exec_with_args(
            GENESIS_ADDR,
            "main_purse.wasm",
            DEFAULT_BLOCK_TIME,
            1,
            genesis_account.purse_id(),
        )
        .expect_success()
        .commit();
}

#[ignore]
#[test]
fn should_run_main_purse_contract_account_1() {
    let account_key = Key::Account(ACCOUNT_1_ADDR);

    let mut builder = WasmTestBuilder::default();

    let builder = builder
        .run_genesis(GENESIS_ADDR, HashMap::new())
        .exec_with_args(
            GENESIS_ADDR,
            "transfer_to_account_01.wasm",
            DEFAULT_BLOCK_TIME,
            1,
            ACCOUNT_1_ADDR,
        )
        .expect_success()
        .commit();

    let account_1: Account = {
        let tmp = builder.clone();
        let transforms = tmp.get_transforms();
        test_support::get_account(&transforms[0], &account_key).expect("should get account")
    };

    builder
        .exec_with_args(
            ACCOUNT_1_ADDR,
            "main_purse.wasm",
            DEFAULT_BLOCK_TIME,
            1,
            account_1.purse_id(),
        )
        .expect_success()
        .commit();
}
