use engine_test_support::low_level::{
    DeployItemBuilder, ExecuteRequestBuilder, InMemoryWasmTestBuilder, DEFAULT_ACCOUNT_ADDR,
    DEFAULT_GENESIS_CONFIG,
};
use types::{account::PublicKey, Phase};

#[ignore]
#[test]
fn should_run_get_phase_contract() {
    let default_account = PublicKey::new(DEFAULT_ACCOUNT_ADDR);

    let exec_request = {
        let deploy = DeployItemBuilder::new()
            .with_address(DEFAULT_ACCOUNT_ADDR)
            .with_deploy_hash([1; 32])
            .with_session_code("get_phase.wasm", (Phase::Session,))
            .with_payment_code("get_phase_payment.wasm", (Phase::Payment,))
            .with_authorization_keys(&[default_account])
            .build();

        ExecuteRequestBuilder::new().push_deploy(deploy).build()
    };

    InMemoryWasmTestBuilder::default()
        .run_genesis(&DEFAULT_GENESIS_CONFIG)
        .exec(exec_request)
        .commit()
        .expect_success();
}
