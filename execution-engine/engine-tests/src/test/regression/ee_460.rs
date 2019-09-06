use contract_ffi::value::U512;
use std::collections::HashMap;

use crate::support::test_support::{
    WasmTestBuilder, DEFAULT_BLOCK_TIME, STANDARD_PAYMENT_CONTRACT,
};
use engine_core::engine_state::MAX_PAYMENT;
use engine_shared::transform::Transform;

const GENESIS_ADDR: [u8; 32] = [6u8; 32];

#[ignore]
#[test]
fn should_run_ee_460_no_side_effects_on_error_regression() {
    let result = WasmTestBuilder::default()
        .run_genesis(GENESIS_ADDR, HashMap::new())
        .exec_with_args(
            GENESIS_ADDR,
            STANDARD_PAYMENT_CONTRACT,
            (U512::from(MAX_PAYMENT),),
            "ee_460_regression.wasm",
            (U512::max_value(),),
            DEFAULT_BLOCK_TIME,
            1,
        )
        .expect_success()
        .commit()
        .finish();

    // In this regression test it is verified that no new urefs are created on the
    // mint uref, which should mean no new purses are created in case of
    // transfer error. This is considered sufficient cause to confirm that the
    // mint uref is left untouched.
    let mint_contract_uref = result.builder().get_mint_contract_uref();

    let transforms = &result.builder().get_transforms()[0];
    let mint_transforms = transforms
        .get(&mint_contract_uref.into())
        // Skips the Identity writes introduced since payment code execution for brevity of the
        // check
        .filter(|&v| v != &Transform::Identity);
    assert!(mint_transforms.is_none());
}
