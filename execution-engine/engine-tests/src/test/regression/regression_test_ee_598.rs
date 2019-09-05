extern crate casperlabs_engine_grpc_server;
extern crate contract_ffi;
extern crate engine_core;
extern crate engine_shared;
extern crate engine_storage;
extern crate grpc;

use std::collections::HashMap;

use contract_ffi::value::account::PublicKey;
use contract_ffi::value::U512;

use test_support::{WasmTestBuilder, DEFAULT_BLOCK_TIME};

#[allow(unused)]
mod test_support;

const GENESIS_ADDR: [u8; 32] = [6u8; 32];
const ACCOUNT_1_ADDR: [u8; 32] = [7u8; 32];

const GENESIS_VALIDATOR_BOND: u64 = 50_000;
const ACCOUNT_1_BALANCE: u64 = 100_000;
const ACCOUNT_1_BOND: u64 = 25_000;

#[ignore]
#[test]
fn should_fail_unboding_more_than_it_was_staked_ee_598_regression() {
    let genesis_validators = {
        let mut result = HashMap::new();
        result.insert(PublicKey::new([42; 32]), U512::from(GENESIS_VALIDATOR_BOND));
        result
    };

    let result = WasmTestBuilder::default()
        .run_genesis(GENESIS_ADDR, genesis_validators)
        .exec_with_args(
            GENESIS_ADDR,
            "pos_bonding.wasm",
            DEFAULT_BLOCK_TIME,
            1,
            (
                String::from("seed_new_account"),
                PublicKey::new(ACCOUNT_1_ADDR),
                U512::from(ACCOUNT_1_BALANCE),
            ),
        )
        .expect_success()
        .commit()
        .exec_with_args(
            ACCOUNT_1_ADDR,
            "ee_598_regression.wasm",
            DEFAULT_BLOCK_TIME,
            1,
            (U512::from(ACCOUNT_1_BOND),),
        )
        .commit()
        .finish();

    let response = result
        .builder()
        .get_exec_response(1)
        .expect("should have a response")
        .to_owned();
    let error_message = {
        let execution_result = test_support::get_success_result(&response);
        test_support::get_error_message(execution_result)
    };
    // Error::UnbondTooLarge => 6,
    assert_eq!(error_message, "Exit code: 6");
}
