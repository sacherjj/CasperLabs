use std::collections::HashMap;

use crate::support::test_support::{
    WasmTestBuilder, DEFAULT_BLOCK_TIME, STANDARD_PAYMENT_CONTRACT,
};
use contract_ffi::value::account::Weight;
use contract_ffi::value::U512;
use engine_core::engine_state::MAX_PAYMENT;

const GENESIS_ADDR: [u8; 32] = [6u8; 32];

#[ignore]
#[test]
fn should_run_ee_539_serialize_action_thresholds_regression() {
    // This test runs a contract that's after every call extends the same key with
    // more data
    let _result = WasmTestBuilder::default()
        .run_genesis(GENESIS_ADDR, HashMap::new())
        .exec_with_args(
            GENESIS_ADDR,
            STANDARD_PAYMENT_CONTRACT,
            (U512::from(MAX_PAYMENT),),
            "ee_539_regression.wasm",
            (Weight::new(4), Weight::new(3)),
            DEFAULT_BLOCK_TIME,
            1,
        )
        .expect_success()
        .commit()
        .finish();
}
