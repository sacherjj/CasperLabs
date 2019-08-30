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

const GENESIS_VALIDATOR_BOND: u64 = 50_000;

#[ignore]
#[test]
fn should_fail_when_bonding_amount_is_zero_ee_597_regression() {
    let genesis_validators = {
        let mut result = HashMap::new();
        result.insert(PublicKey::new([42; 32]), U512::from(GENESIS_VALIDATOR_BOND));
        result
    };

    let result = WasmTestBuilder::default()
        .run_genesis(GENESIS_ADDR, genesis_validators)
        .exec(
            GENESIS_ADDR,
            "ee_597_regression.wasm",
            DEFAULT_BLOCK_TIME,
            1,
        )
        .commit()
        .finish();

    let response = result
        .builder()
        .get_exec_response(0)
        .expect("should have a response")
        .to_owned();

    let error_message = {
        let execution_result = test_support::get_success_result(&response);
        test_support::get_error_message(execution_result)
    };
    // Error::BondTooSmall => 9,
    assert_eq!(error_message, "Exit code: 9");
}
