extern crate casperlabs_engine_grpc_server;
extern crate contract_ffi;
extern crate engine_core;
extern crate engine_shared;
extern crate engine_storage;
extern crate grpc;

use std::collections::HashMap;

use test_support::{WasmTestBuilder, DEFAULT_BLOCK_TIME};

#[allow(dead_code)]
mod test_support;

const GENESIS_ADDR: [u8; 32] = [7u8; 32];
const ACCOUNT_1_ADDR: [u8; 32] = [1u8; 32];

#[ignore]
#[test]
fn should_have_read_only_access_to_system_contract_urefs() {
    let mut builder = WasmTestBuilder::default();

    builder
        .run_genesis(GENESIS_ADDR, HashMap::new())
        .exec_with_args(
            GENESIS_ADDR,
            "transfer_to_account_01.wasm",
            DEFAULT_BLOCK_TIME,
            1,
            ACCOUNT_1_ADDR,
        )
        .commit()
        .exec(
            ACCOUNT_1_ADDR,
            "check_system_contract_urefs_access_rights.wasm",
            DEFAULT_BLOCK_TIME,
            1,
        )
        .commit()
        .expect_success();
}
