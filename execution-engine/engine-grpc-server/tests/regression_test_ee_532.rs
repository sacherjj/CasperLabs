extern crate casperlabs_engine_grpc_server;
extern crate contract_ffi;
extern crate engine_core;
extern crate engine_shared;
extern crate engine_storage;
extern crate grpc;

use std::collections::HashMap;

use engine_core::engine_state::error;
use test_support::{WasmTestBuilder, DEFAULT_BLOCK_TIME};

#[allow(dead_code)]
mod test_support;

const GENESIS_ADDR: [u8; 32] = [6u8; 32];
const UNKNOWN_ADDR: [u8; 32] = [42u8; 32];

#[ignore]
#[test]
fn should_run_ee_532_get_uref_regression_test() {
    // This test runs a contract that's after every call extends the same key with more data
    let result = WasmTestBuilder::default()
        .run_genesis(GENESIS_ADDR, HashMap::new())
        .exec(
            UNKNOWN_ADDR,
            "ee_532_regression.wasm",
            DEFAULT_BLOCK_TIME,
            1,
        )
        .commit()
        .finish();
    let deploy_result = result
        .builder()
        .get_exec_response(0)
        .expect("should have exec response")
        .get_success()
        .get_deploy_results()
        .get(0)
        .expect("should have at least one deploy result");

    assert!(deploy_result.has_precondition_failure());
    let message = deploy_result.get_precondition_failure().get_message();

    assert_eq!(message, format!("{}", error::Error::AuthorizationError))
}
