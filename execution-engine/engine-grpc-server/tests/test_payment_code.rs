extern crate grpc;

extern crate casperlabs_engine_grpc_server;
extern crate contract_ffi;
extern crate engine_core;
extern crate engine_shared;
extern crate engine_storage;

use std::collections::HashMap;

use contract_ffi::value::account::PublicKey;
use contract_ffi::value::U512;

use casperlabs_engine_grpc_server::engine_server::ipc::{DeployResult, ExecResponse_oneof_result};
use engine_core::engine_state::EngineConfig;
use test_support::{DeployBuilder, ExecRequestBuilder, WasmTestBuilder};

#[allow(dead_code)]
mod test_support;

const GENESIS_ADDR: [u8; 32] = [0; 32];
const ACCOUNT_1_ADDR: [u8; 32] = [42u8; 32];

#[ignore]
#[test]
fn should_execute_payment_code() {
    let genesis_public_key = PublicKey::new(GENESIS_ADDR);
    let account_1_public_key = PublicKey::new(ACCOUNT_1_ADDR);

    let engine_config = EngineConfig::new().set_use_payment_code(true);

    let exec_request = {
        let deploy = DeployBuilder::new()
            .with_address(GENESIS_ADDR)
            .with_session_code(
                "transfer_purse_to_account.wasm",
                (account_1_public_key, U512::from(42)),
            )
            .with_payment_code("standard_payment.wasm", U512::from(10_000_000))
            .with_authorization_keys(&[genesis_public_key])
            .with_nonce(1)
            .build();

        ExecRequestBuilder::new().push_deploy(deploy).build()
    };

    let _transfer_result = WasmTestBuilder::new(engine_config)
        .run_genesis(GENESIS_ADDR, HashMap::default())
        .exec_with_exec_request(exec_request)
        .expect_success()
        .commit()
        .finish();
}

#[ignore]
#[test]
fn should_not_charge_for_invalid_nonce() {
    let genesis_public_key = PublicKey::new(GENESIS_ADDR);
    let account_1_public_key = PublicKey::new(ACCOUNT_1_ADDR);

    let invalid_nonce = 2;

    let engine_config = EngineConfig::new().set_use_payment_code(true);

    let exec_request = {
        let deploy = DeployBuilder::new()
            .with_address(GENESIS_ADDR)
            .with_session_code(
                "transfer_purse_to_account.wasm",
                (account_1_public_key, U512::from(42)),
            )
            .with_payment_code("standard_payment.wasm", U512::from(10_000_000))
            .with_authorization_keys(&[genesis_public_key])
            .with_nonce(invalid_nonce)
            .build();

        ExecRequestBuilder::new().push_deploy(deploy).build()
    };

    let transfer_result = WasmTestBuilder::new(engine_config)
        .run_genesis(GENESIS_ADDR, HashMap::default())
        .exec_with_exec_request(exec_request)
        .commit()
        .finish();

    let response = transfer_result
        .builder()
        .get_exec_response(0)
        .expect("there should be a response")
        .clone();

    let has_invalid_nonce: bool = match &response.result.expect("result expected") {
        ExecResponse_oneof_result::success(result) => {
            let first: &DeployResult = result
                .deploy_results
                .first()
                .expect("should have a deploy result");
            first.has_invalid_nonce()
        }
        ExecResponse_oneof_result::missing_parent(_) => false,
    };

    assert!(has_invalid_nonce, "expected invalid nonce");

    let transforms = transfer_result.builder().get_transforms();
    let transform = &transforms[0];

    assert_eq!(
        transform.len(),
        0,
        "there should be no transforms as there are no changes (including charging for exec)"
    );
}
