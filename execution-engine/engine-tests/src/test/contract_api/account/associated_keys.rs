use contract_ffi::key::Key;
use contract_ffi::value::account::{PublicKey, Weight};
use contract_ffi::value::{Account, U512};
use engine_core::engine_state::MAX_PAYMENT;

use crate::support::test_support::{
    InMemoryWasmTestBuilder, DEFAULT_BLOCK_TIME, STANDARD_PAYMENT_CONTRACT,
};
use crate::test::{DEFAULT_ACCOUNT_ADDR, DEFAULT_GENESIS_CONFIG};

const ACCOUNT_1_ADDR: [u8; 32] = [1u8; 32];
const ACCOUNT_1_INITIAL_BALANCE: u64 = MAX_PAYMENT * 2;

#[ignore]
#[test]
fn should_manage_associated_key() {
    // for a given account, should be able to add a new associated key and update
    // that key
    let mut builder = InMemoryWasmTestBuilder::default();

    let builder = builder
        .run_genesis(&DEFAULT_GENESIS_CONFIG)
        .exec_with_args(
            DEFAULT_ACCOUNT_ADDR,
            STANDARD_PAYMENT_CONTRACT,
            (U512::from(MAX_PAYMENT),),
            "transfer_purse_to_account.wasm",
            (ACCOUNT_1_ADDR, U512::from(ACCOUNT_1_INITIAL_BALANCE)),
            DEFAULT_BLOCK_TIME,
            [1u8; 32],
        )
        .expect_success()
        .commit()
        .exec_with_args(
            ACCOUNT_1_ADDR,
            STANDARD_PAYMENT_CONTRACT,
            (U512::from(MAX_PAYMENT),),
            "add_update_associated_key.wasm",
            (DEFAULT_ACCOUNT_ADDR,),
            DEFAULT_BLOCK_TIME,
            [2u8; 32],
        )
        .expect_success()
        .commit();

    let account_key = Key::Account(ACCOUNT_1_ADDR);
    let genesis_key = PublicKey::new(DEFAULT_ACCOUNT_ADDR);

    let account_1: Account = {
        let tmp = builder.clone();
        let transforms = tmp.get_transforms();
        crate::support::test_support::get_account(&transforms[1], &account_key)
            .expect("should get account")
    };

    let gen_weight = account_1
        .get_associated_key_weight(genesis_key)
        .expect("weight");

    let expected_weight = Weight::new(2);
    assert_eq!(*gen_weight, expected_weight, "unexpected weight");

    builder
        .exec_with_args(
            ACCOUNT_1_ADDR,
            STANDARD_PAYMENT_CONTRACT,
            (U512::from(MAX_PAYMENT),),
            "remove_associated_key.wasm",
            (DEFAULT_ACCOUNT_ADDR,),
            DEFAULT_BLOCK_TIME,
            [3u8; 32],
        )
        .expect_success()
        .commit();

    let account_1: Account = {
        let tmp = builder.clone();
        let transforms = tmp.get_transforms();
        crate::support::test_support::get_account(&transforms[2], &account_key)
            .expect("should get account")
    };

    assert_eq!(
        account_1.get_associated_key_weight(genesis_key),
        None,
        "key should be removed"
    );

    let is_error = builder.is_error();
    assert!(!is_error);
}
