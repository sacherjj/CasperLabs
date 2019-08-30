use std::collections::HashMap;

use crate::support::test_support::{WasmTestBuilder, DEFAULT_BLOCK_TIME};

const GENESIS_ADDR: [u8; 32] = [7u8; 32];

#[ignore]
#[test]
fn should_not_fail_deserializing() {
    let is_error = WasmTestBuilder::default()
        .run_genesis(GENESIS_ADDR, HashMap::new())
        .exec_with_args(
            GENESIS_ADDR,
            "deserialize_error.wasm",
            DEFAULT_BLOCK_TIME,
            1,
            (GENESIS_ADDR,),
        )
        .commit()
        .is_error();

    assert!(is_error);
}
