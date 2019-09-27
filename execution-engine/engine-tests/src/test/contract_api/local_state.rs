use contract_ffi::bytesrepr::ToBytes;
use contract_ffi::key::Key;
use contract_ffi::value::account::PublicKey;
use contract_ffi::value::Value;
use contract_ffi::value::U512;
use engine_core::engine_state::MAX_PAYMENT;
use engine_shared::transform::Transform;

use crate::support::test_support::{
    DeployBuilder, ExecRequestBuilder, InMemoryWasmTestBuilder, STANDARD_PAYMENT_CONTRACT,
};
use crate::test::{DEFAULT_ACCOUNT_ADDR, DEFAULT_GENESIS_CONFIG};

#[ignore]
#[test]
fn should_run_local_state_contract() {
    let exec_request_1 = {
        let deploy = DeployBuilder::new()
            .with_address(DEFAULT_ACCOUNT_ADDR)
            .with_payment_code(STANDARD_PAYMENT_CONTRACT, (U512::from(MAX_PAYMENT),))
            .with_session_code("local_state.wasm", ())
            .with_deploy_hash([1u8; 32])
            .with_authorization_keys(&[PublicKey::new(DEFAULT_ACCOUNT_ADDR)])
            .build();
        ExecRequestBuilder::from_deploy(deploy).build()
    };

    let exec_request_2 = {
        let deploy = DeployBuilder::new()
            .with_address(DEFAULT_ACCOUNT_ADDR)
            .with_payment_code(STANDARD_PAYMENT_CONTRACT, (U512::from(MAX_PAYMENT),))
            .with_session_code("local_state.wasm", ())
            .with_deploy_hash([2u8; 32])
            .with_authorization_keys(&[PublicKey::new(DEFAULT_ACCOUNT_ADDR)])
            .build();
        ExecRequestBuilder::from_deploy(deploy).build()
    };

    // This test runs a contract that's after every call extends the same key with
    // more data
    let result = InMemoryWasmTestBuilder::default()
        .run_genesis(&DEFAULT_GENESIS_CONFIG)
        .exec_with_exec_request(exec_request_1)
        .expect_success()
        .commit()
        .exec_with_exec_request(exec_request_2)
        .expect_success()
        .commit()
        .finish();

    let transforms = result.builder().get_transforms();

    let expected_local_key = Key::local(DEFAULT_ACCOUNT_ADDR, &[66u8; 32].to_bytes().unwrap());

    assert_eq!(transforms.len(), 2);
    assert_eq!(
        transforms[0]
            .get(&expected_local_key)
            .expect("Should have expected local key"),
        &Transform::Write(Value::String(String::from("Hello, world!")))
    );

    assert_eq!(
        transforms[1]
            .get(&expected_local_key)
            .expect("Should have expected local key"),
        &Transform::Write(Value::String(String::from("Hello, world! Hello, world!")))
    );
}
