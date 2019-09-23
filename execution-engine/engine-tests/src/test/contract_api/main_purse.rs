use std::collections::HashMap;

use crate::support::test_support::{
    InMemoryWasmTestBuilder, DEFAULT_BLOCK_TIME, STANDARD_PAYMENT_CONTRACT,
};
use contract_ffi::key::Key;
use contract_ffi::value::U512;
use contract_ffi::value::{Account, Value};
use engine_core::engine_state::MAX_PAYMENT;

const GENESIS_ADDR: [u8; 32] = [6u8; 32];
const ACCOUNT_1_ADDR: [u8; 32] = [1u8; 32];
const ACCOUNT_1_INITIAL_BALANCE: u64 = MAX_PAYMENT;

#[ignore]
#[test]
fn should_run_main_purse_contract_genesis_account() {
    let mut builder = InMemoryWasmTestBuilder::default();

    let builder = builder.run_genesis(GENESIS_ADDR, HashMap::new());

    let genesis_account = if let Some(Value::Account(account)) =
        builder.query(None, Key::Account(GENESIS_ADDR), &[])
    {
        account
    } else {
        panic!("could not get account")
    };

    builder
        .exec_with_args(
            GENESIS_ADDR,
            STANDARD_PAYMENT_CONTRACT,
            (U512::from(MAX_PAYMENT),),
            "main_purse.wasm",
            (genesis_account.purse_id(), ()),
            DEFAULT_BLOCK_TIME,
            [1u8; 32],
        )
        .expect_success()
        .commit();
}

#[ignore]
#[test]
fn should_run_main_purse_contract_account_1() {
    let account_key = Key::Account(ACCOUNT_1_ADDR);

    let mut builder = InMemoryWasmTestBuilder::default();

    let builder = builder
        .run_genesis(GENESIS_ADDR, HashMap::new())
        .exec_with_args(
            GENESIS_ADDR,
            STANDARD_PAYMENT_CONTRACT,
            (U512::from(MAX_PAYMENT),),
            "transfer_purse_to_account.wasm",
            (ACCOUNT_1_ADDR, U512::from(ACCOUNT_1_INITIAL_BALANCE)),
            DEFAULT_BLOCK_TIME,
            [1u8; 32],
        )
        .expect_success()
        .commit();

    let account_1: Account = {
        let tmp = builder.clone();
        let transforms = tmp.get_transforms();
        crate::support::test_support::get_account(&transforms[0], &account_key)
            .expect("should get account")
    };

    builder
        .exec_with_args(
            ACCOUNT_1_ADDR,
            STANDARD_PAYMENT_CONTRACT,
            (U512::from(MAX_PAYMENT),),
            "main_purse.wasm",
            (account_1.purse_id(),),
            DEFAULT_BLOCK_TIME,
            [1u8; 32],
        )
        .expect_success()
        .commit();
}
