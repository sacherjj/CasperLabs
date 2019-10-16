use contract_ffi::value::account::PublicKey;

use crate::support::test_support::{
    DeployItemBuilder, ExecuteRequestBuilder, InMemoryWasmTestBuilder,
};
use crate::test::{
    CONTRACT_STANDARD_PAYMENT, DEFAULT_ACCOUNT_ADDR, DEFAULT_GENESIS_CONFIG, DEFAULT_PAYMENT,
};

const CONTRACT_EE_550_REGRESSION: &str = "ee_550_regression.wasm";

#[ignore]
#[test]
fn should_run_ee_550_remove_with_saturated_threshold_regression() {
    let exec_request_1 = ExecuteRequestBuilder::standard(
        DEFAULT_ACCOUNT_ADDR,
        CONTRACT_EE_550_REGRESSION,
        (String::from("init"),),
    )
    .build();

    let exec_request_2 = {
        let deploy_item = DeployItemBuilder::new()
            .with_address(DEFAULT_ACCOUNT_ADDR)
            .with_session_code(CONTRACT_EE_550_REGRESSION, (String::from("test"),))
            .with_payment_code(CONTRACT_STANDARD_PAYMENT, (*DEFAULT_PAYMENT,))
            .with_authorization_keys(&[
                PublicKey::new(DEFAULT_ACCOUNT_ADDR),
                PublicKey::new([101; 32]),
            ])
            .with_deploy_hash([42; 32])
            .build();

        ExecuteRequestBuilder::from_deploy_item(deploy_item).build()
    };

    let mut builder = InMemoryWasmTestBuilder::default();

    builder
        .run_genesis(&DEFAULT_GENESIS_CONFIG)
        .exec(exec_request_1)
        .expect_success()
        .commit()
        .exec(exec_request_2)
        .expect_success()
        .commit();
}
