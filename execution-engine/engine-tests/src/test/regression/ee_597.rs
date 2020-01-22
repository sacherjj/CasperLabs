use engine_test_support::low_level::{
    utils, ExecuteRequestBuilder, InMemoryWasmTestBuilder, DEFAULT_ACCOUNT_ADDR,
    DEFAULT_GENESIS_CONFIG,
};
use types::ApiError;

const CONTRACT_EE_597_REGRESSION: &str = "ee_597_regression.wasm";

#[ignore]
#[test]
fn should_fail_when_bonding_amount_is_zero_ee_597_regression() {
    let exec_request =
        ExecuteRequestBuilder::standard(DEFAULT_ACCOUNT_ADDR, CONTRACT_EE_597_REGRESSION, ())
            .build();

    let result = InMemoryWasmTestBuilder::default()
        .run_genesis(&DEFAULT_GENESIS_CONFIG)
        .exec(exec_request)
        .commit()
        .finish();

    let response = result
        .builder()
        .get_exec_response(0)
        .expect("should have a response")
        .to_owned();

    let error_message = {
        let execution_result = utils::get_success_result(&response);
        utils::get_error_message(execution_result)
    };
    // Error::BondTooSmall => 5,
    assert_eq!(
        error_message,
        format!("Exit code: {}", u32::from(ApiError::ProofOfStake(5)))
    );
}
