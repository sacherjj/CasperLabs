extern crate casperlabs_engine_grpc_server;
extern crate contract_ffi;
extern crate engine_core;
extern crate engine_shared;
extern crate engine_storage;
extern crate grpc;

use contract_ffi::value::{Value, U512};
use engine_shared::transform::Transform;
use std::collections::HashMap;

use test_support::{WasmTestBuilder, DEFAULT_BLOCK_TIME};
#[allow(dead_code)]
mod test_support;

const GENESIS_ADDR: [u8; 32] = [6u8; 32];

#[ignore]
#[test]
fn should_run_ee_460_no_side_effects_on_error_regression() {
    // This test runs a contract that's after every call extends the same key with more data
    let result = WasmTestBuilder::default()
        .run_genesis(GENESIS_ADDR, HashMap::new())
        .exec_with_args(
            GENESIS_ADDR,
            "ee_460_regression.wasm",
            DEFAULT_BLOCK_TIME,
            1,
            U512::max_value(),
        )
        .commit()
        .finish();

    // In this regression test it is verified that no new urefs are created on the mint uref,
    // which should mean no new purses are created in case of transfer error. This is considered
    // sufficient cause to confirm that the mint uref is left untouched.
    let mint_contract_uref = result.builder().get_mint_contract_uref();

    let transforms = &result.builder().get_transforms()[0];
    assert!(transforms.get(&mint_contract_uref.into()).is_none());

    // Tries to transfer exactly as much tokens as the genesis account is initialized with
    let result = WasmTestBuilder::default()
        .run_genesis(GENESIS_ADDR, HashMap::new())
        .exec_with_args(
            GENESIS_ADDR,
            "ee_460_regression.wasm",
            DEFAULT_BLOCK_TIME,
            1,
            U512::from(test_support::GENESIS_INITIAL_BALANCE),
        )
        .expect_success()
        .commit()
        .finish();

    // Verify that in case of successful transfer where user is sending all their tokens to a new account a new purse is created
    // with correct balance. Checking if transforms are made on mint uref should be sufficient to prove the precondition check
    // is working already.
    let mint_contract_uref = result.builder().get_mint_contract_uref();

    let transforms = &result.builder().get_transforms()[0];
    let mint_transform = transforms
        .get(&mint_contract_uref.into())
        .expect("mint should have transforms");
    let new_keys = if let Transform::AddKeys(keys) = mint_transform {
        keys
    } else {
        panic!("mint transfer should be a AddKeys variant");
    };

    // First AddKeys is the new purses balance uref
    let (_key, value) = new_keys.iter().nth(0).unwrap();
    let uref_value = transforms
        .get(&value.normalize())
        .expect("should contain transform value");
    let balance = if let Transform::Write(Value::UInt512(value)) = uref_value {
        value
    } else {
        panic!("should be a Write transform of Uint512 variant");
    };
    assert_eq!(balance, &U512::from(test_support::GENESIS_INITIAL_BALANCE));
}
