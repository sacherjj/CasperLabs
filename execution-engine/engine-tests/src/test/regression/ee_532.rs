use crate::support::test_support::{
    DeployItemBuilder, ExecuteRequestBuilder, InMemoryWasmTestBuilder, STANDARD_PAYMENT_CONTRACT,
};
use crate::test::DEFAULT_GENESIS_CONFIG;
use contract_ffi::value::account::PublicKey;
use contract_ffi::value::U512;
use engine_core::engine_state::{error, MAX_PAYMENT};

const UNKNOWN_ADDR: [u8; 32] = [42u8; 32];

#[ignore]
#[test]
fn should_run_ee_532_get_uref_regression_test() {
    // This test runs a contract that's after every call extends the same key with
    // more data

    let exec_request = {
        let deploy = DeployItemBuilder::new()
            .with_address(UNKNOWN_ADDR)
            .with_payment_code(STANDARD_PAYMENT_CONTRACT, (U512::from(MAX_PAYMENT),))
            .with_session_code("ee_532_regression.wasm", ())
            .with_deploy_hash([1u8; 32])
            .with_authorization_keys(&[PublicKey::new(UNKNOWN_ADDR)])
            .build();
        ExecuteRequestBuilder::from_deploy_item(deploy).build()
    };

    let result = InMemoryWasmTestBuilder::default()
        .run_genesis(&DEFAULT_GENESIS_CONFIG)
        .exec_with_exec_request(exec_request)
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

    assert!(
        deploy_result.has_precondition_failure(),
        "expected precondition failure"
    );

    let message = deploy_result.get_precondition_failure().get_message();
    assert_eq!(
        message,
        format!("{}", error::Error::AuthorizationError),
        "expected AuthorizationError"
    )
}
