use std::collections::HashMap;

use crate::support::test_support::{WasmTestBuilder, STANDARD_PAYMENT_CONTRACT};
use contract_ffi::value::U512;
use engine_core::engine_state::MAX_PAYMENT;

const GENESIS_ADDR: [u8; 32] = [7u8; 32];

#[ignore]
#[test]
fn should_run_get_blocktime_contract() {
    let block_time: u64 = 42;

    WasmTestBuilder::default()
        .run_genesis(GENESIS_ADDR, HashMap::new())
        .exec_with_args(
            GENESIS_ADDR,
            STANDARD_PAYMENT_CONTRACT,
            (U512::from(MAX_PAYMENT),),
            "get_blocktime.wasm",
            (block_time,), // passing this to contract to test assertion
            block_time,
            1,
        )
        .commit()
        .expect_success();
}
