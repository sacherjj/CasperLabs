extern crate casperlabs_engine_grpc_server;
extern crate common;
extern crate execution_engine;
extern crate grpc;
extern crate shared;
extern crate storage;

use std::collections::HashMap;

use common::key::Key;
use common::value::account::{PublicKey, Weight};
use common::value::Account;
use test_support::{WasmTestBuilder, DEFAULT_BLOCK_TIME};

#[allow(dead_code)]
mod test_support;

const GENESIS_ADDR: [u8; 32] = [7u8; 32];
const ACCOUNT_1_ADDR: [u8; 32] = [1u8; 32];

#[ignore]
#[test]
fn should_manage_associated_key() {
    // for a given account, should be able to add a new associated key and update that key
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
        .commit()
        .exec_with_args(
            ACCOUNT_1_ADDR,
            "add_update_associated_key.wasm",
            DEFAULT_BLOCK_TIME,
            1,
            GENESIS_ADDR,
        )
        .expect_success()
        .commit();

    let account_key = Key::Account(ACCOUNT_1_ADDR);
    let genesis_key = PublicKey::new(GENESIS_ADDR);

    let account_1: Account = {
        let tmp = builder.clone();
        let transforms = tmp.get_transforms();
        test_support::get_account(&transforms[1], &account_key).expect("should get account")
    };

    let gen_weight = account_1
        .get_associated_key_weight(genesis_key)
        .expect("weight");

    let expected_weight = Weight::new(2);
    assert_eq!(*gen_weight, expected_weight, "unexpected weight");

    builder
        .exec_with_args(
            ACCOUNT_1_ADDR,
            "remove_associated_key.wasm",
            DEFAULT_BLOCK_TIME,
            2,
            GENESIS_ADDR,
        )
        .expect_success()
        .commit();

    let account_1: Account = {
        let tmp = builder.clone();
        let transforms = tmp.get_transforms();
        test_support::get_account(&transforms[2], &account_key).expect("should get account")
    };

    assert_eq!(
        account_1.get_associated_key_weight(genesis_key),
        None,
        "key should be removed"
    );

    let is_error = builder.is_error();
    assert!(!is_error);
}
