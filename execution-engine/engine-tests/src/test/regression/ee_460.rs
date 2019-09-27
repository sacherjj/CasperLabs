use crate::support::test_support::{
    DeployBuilder, ExecRequestBuilder, InMemoryWasmTestBuilder, STANDARD_PAYMENT_CONTRACT,
};
use crate::test::{DEFAULT_ACCOUNT_ADDR, DEFAULT_GENESIS_CONFIG};
use contract_ffi::value::account::PublicKey;
use contract_ffi::value::U512;
use engine_core::engine_state::MAX_PAYMENT;
use engine_shared::transform::Transform;

#[ignore]
#[test]
fn should_run_ee_460_no_side_effects_on_error_regression() {
    let exec_request_1 = {
        let deploy = DeployBuilder::new()
            .with_address(DEFAULT_ACCOUNT_ADDR)
            .with_payment_code(STANDARD_PAYMENT_CONTRACT, (U512::from(MAX_PAYMENT),))
            .with_session_code("ee_460_regression.wasm", (U512::max_value(),))
            .with_deploy_hash([1u8; 32])
            .with_authorization_keys(&[PublicKey::new(DEFAULT_ACCOUNT_ADDR)])
            .build();
        ExecRequestBuilder::from_deploy(deploy).build()
    };
    let result = InMemoryWasmTestBuilder::default()
        .run_genesis(&DEFAULT_GENESIS_CONFIG)
        .exec_with_exec_request(exec_request_1)
        .expect_success()
        .commit()
        .finish();

    // In this regression test it is verified that no new urefs are created on the
    // mint uref, which should mean no new purses are created in case of
    // transfer error. This is considered sufficient cause to confirm that the
    // mint uref is left untouched.
    let mint_contract_uref = result.builder().get_mint_contract_uref();

    let transforms = &result.builder().get_transforms()[0];
    let mint_transforms = transforms
        .get(&mint_contract_uref.into())
        // Skips the Identity writes introduced since payment code execution for brevity of the
        // check
        .filter(|&v| v != &Transform::Identity);
    assert!(mint_transforms.is_none());
}
