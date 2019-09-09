use contract_ffi::value::U512;
use engine_core::engine_state::MAX_PAYMENT;
use std::collections::HashMap;

use crate::support::test_support::{
    DEFAULT_BLOCK_TIME, STANDARD_PAYMENT_CONTRACT, WasmTestBuilder,
};

const GENESIS_ADDR: [u8; 32] = [7u8; 32];

#[ignore]
#[test]
fn should_not_fail_deserializing() {
    let is_error = WasmTestBuilder::default()
        .run_genesis(GENESIS_ADDR, HashMap::new())
        .exec_with_args(
            GENESIS_ADDR,
            STANDARD_PAYMENT_CONTRACT,
            (U512::from(MAX_PAYMENT), ),
            "deserialize_error.wasm",
            (GENESIS_ADDR, ),
            DEFAULT_BLOCK_TIME,
            [1u8; 32],
        )
        .commit()
        .is_error();

    assert!(is_error);
}
