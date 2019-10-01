use crate::support::test_support::{
    DeployItemBuilder, ExecuteRequestBuilder, InMemoryWasmTestBuilder, STANDARD_PAYMENT_CONTRACT,
};
use contract_ffi::value::account::PublicKey;
use contract_ffi::value::U512;
use engine_core::engine_state::MAX_PAYMENT;

use crate::test::{DEFAULT_ACCOUNT_ADDR, DEFAULT_GENESIS_CONFIG};

#[ignore]
#[test]
fn should_execute_contracts_which_provide_extra_urefs() {
    let exec_request_1 = {
        let deploy = DeployItemBuilder::new()
            .with_address(DEFAULT_ACCOUNT_ADDR)
            .with_payment_code(STANDARD_PAYMENT_CONTRACT, (U512::from(MAX_PAYMENT),))
            .with_session_code("ee_401_regression.wasm", ())
            .with_deploy_hash([1u8; 32])
            .with_authorization_keys(&[PublicKey::new(DEFAULT_ACCOUNT_ADDR)])
            .build();
        ExecuteRequestBuilder::from_deploy_item(deploy).build()
    };

    let exec_request_2 = {
        let deploy = DeployItemBuilder::new()
            .with_address(DEFAULT_ACCOUNT_ADDR)
            .with_payment_code(STANDARD_PAYMENT_CONTRACT, (U512::from(MAX_PAYMENT),))
            .with_session_code(
                "ee_401_regression_call.wasm",
                (PublicKey::new(DEFAULT_ACCOUNT_ADDR),),
            )
            .with_deploy_hash([2u8; 32])
            .with_authorization_keys(&[PublicKey::new(DEFAULT_ACCOUNT_ADDR)])
            .build();
        ExecuteRequestBuilder::from_deploy_item(deploy).build()
    };
    let _result = InMemoryWasmTestBuilder::default()
        .run_genesis(&DEFAULT_GENESIS_CONFIG)
        .exec_with_exec_request(exec_request_1)
        .expect_success()
        .commit()
        .exec_with_exec_request(exec_request_2)
        .expect_success()
        .commit()
        .finish();
}
