use std::collections::HashMap;
use casperlabs_engine_tests::support::test_support::{WasmTestBuilder, DEFAULT_BLOCK_TIME};
use contract_ffi::value::U512;

const GENESIS_ADDR: [u8; 32] = [1; 32];

fn main() {
    let accounts: Vec<Vec<u8>> = (100u8..=151u8).map(|b| vec![b; 32]).collect();
    let amount: U512 = 1_000_000u64.into();

    let result = WasmTestBuilder::default()
        .run_genesis(GENESIS_ADDR, HashMap::new())
        .exec_with_args(
            GENESIS_ADDR,
            "create_accounts.wasm",
            DEFAULT_BLOCK_TIME, // blocktime
            [1; 32], // nonce
            (accounts, amount), //args
        )
        .expect_success()
        .commit()
        .finish();

    let transforms = result.builder().get_transforms();
    println!("{:#?}", transforms[0]);
}
