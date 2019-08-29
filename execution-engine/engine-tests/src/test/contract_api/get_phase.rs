use std::collections::HashMap;

use crate::support::test_support::{DeployBuilder, ExecRequestBuilder, WasmTestBuilder};
use contract_ffi::execution::Phase;
use contract_ffi::value::account::PublicKey;
use engine_core::engine_state::EngineConfig;

const GENESIS_ADDR: [u8; 32] = [7u8; 32];

#[ignore]
#[test]
fn should_run_get_phase_contract() {
    let genesis_public_key = PublicKey::new(GENESIS_ADDR);

    let engine_config = EngineConfig::new().set_use_payment_code(true);

    let exec_request = {
        let deploy = DeployBuilder::new()
            .with_address(GENESIS_ADDR)
            .with_session_code("get_phase.wasm", (Phase::Session,))
            .with_payment_code("get_phase_payment.wasm", (Phase::Payment,))
            .with_authorization_keys(&[genesis_public_key])
            .with_nonce(1)
            .build();

        ExecRequestBuilder::new().push_deploy(deploy).build()
    };

    WasmTestBuilder::new(engine_config)
        .run_genesis(GENESIS_ADDR, HashMap::default())
        .exec_with_exec_request(exec_request)
        .commit()
        .expect_success();
}
