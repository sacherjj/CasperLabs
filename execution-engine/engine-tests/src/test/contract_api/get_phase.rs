use contract_ffi::execution::Phase;
use contract_ffi::value::account::PublicKey;
use engine_core::engine_state::EngineConfig;

use crate::support::test_support::{DeployBuilder, ExecRequestBuilder, InMemoryWasmTestBuilder};
use crate::test::{DEFAULT_ACCOUNT_ADDR, DEFAULT_GENESIS_CONFIG};

#[ignore]
#[test]
fn should_run_get_phase_contract() {
    let default_account = PublicKey::new(DEFAULT_ACCOUNT_ADDR);

    let engine_config = EngineConfig::new().set_use_payment_code(true);

    let exec_request = {
        let deploy = DeployBuilder::new()
            .with_address(DEFAULT_ACCOUNT_ADDR)
            .with_deploy_hash([1; 32])
            .with_session_code("get_phase.wasm", (Phase::Session,))
            .with_payment_code("get_phase_payment.wasm", (Phase::Payment,))
            .with_authorization_keys(&[default_account])
            .build();

        ExecRequestBuilder::new().push_deploy(deploy).build()
    };

    InMemoryWasmTestBuilder::new(engine_config)
        .run_genesis(&DEFAULT_GENESIS_CONFIG)
        .exec_with_exec_request(exec_request)
        .commit()
        .expect_success();
}
