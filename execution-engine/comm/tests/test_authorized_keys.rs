extern crate casperlabs_engine_grpc_server;
extern crate common;
extern crate execution_engine;
extern crate grpc;
extern crate shared;
extern crate storage;

#[allow(dead_code)]
mod test_support;

use std::collections::HashMap;

use common::value::account::PublicKey;
use execution_engine::engine_state::error;
use test_support::WasmTestBuilder;

const GENESIS_ADDR: [u8; 32] = [7u8; 32];

#[ignore]
#[test]
fn should_deploy_with_authorized_keys() {
    // Basic deploy with single key
    WasmTestBuilder::default()
        .run_genesis(GENESIS_ADDR, HashMap::new())
        .exec_with_args_and_keys(
            GENESIS_ADDR,
            "authorized_keys.wasm",
            1,  // blocktime
            1,  // nonce
            (), //args
            vec![PublicKey::new(GENESIS_ADDR)],
        )
        .commit()
        .expect_success();
}

#[ignore]
#[test]
fn should_raise_auth_failure_with_invalid_keys() {
    // tests that authorized keys that does not belong to account raises AuthorizationFailure
    let key_1 = [254; 32];
    assert_ne!(GENESIS_ADDR, key_1);
    // Basic deploy with single key
    let result = WasmTestBuilder::default()
        .run_genesis(GENESIS_ADDR, HashMap::new())
        .exec_with_args_and_keys(
            GENESIS_ADDR,
            "authorized_keys.wasm",
            1,  // blocktime
            1,  // nonce
            (), //args
            vec![PublicKey::new(key_1)],
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

    assert_eq!(message, format!("{}", error::Error::AuthorizationFailure))
}
