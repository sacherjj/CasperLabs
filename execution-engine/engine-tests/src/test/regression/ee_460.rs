use contract_ffi::value::U512;
use std::collections::HashMap;

use crate::support::test_support::{WasmTestBuilder, DEFAULT_BLOCK_TIME};

const GENESIS_ADDR: [u8; 32] = [6u8; 32];

#[ignore]
#[test]
fn should_run_ee_460_no_side_effects_on_error_regression() {
    let result = WasmTestBuilder::default()
        .run_genesis(GENESIS_ADDR, HashMap::new())
        .exec_with_args(
            GENESIS_ADDR,
            "ee_460_regression.wasm",
            DEFAULT_BLOCK_TIME,
            1,
            (U512::max_value(),),
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
    assert!(transforms.get(&mint_contract_uref.into()).is_none());
}
