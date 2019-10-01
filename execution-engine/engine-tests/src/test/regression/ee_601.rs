use contract_ffi::key::Key;
use contract_ffi::value::account::PublicKey;
use contract_ffi::value::{Value, U512};
use engine_core::engine_state::MAX_PAYMENT;
use engine_shared::transform::Transform;

use crate::support::test_support::{
    DeployItemBuilder, ExecuteRequestBuilder, InMemoryWasmTestBuilder,
};
use crate::test::{DEFAULT_ACCOUNT_ADDR, DEFAULT_GENESIS_CONFIG};

#[ignore]
#[test]
fn should_run_ee_601_pay_session_new_uref_collision() {
    let genesis_public_key = PublicKey::new(DEFAULT_ACCOUNT_ADDR);

    let exec_request = {
        let deploy = DeployItemBuilder::new()
            .with_deploy_hash([1; 32])
            .with_address(DEFAULT_ACCOUNT_ADDR)
            .with_payment_code("ee_601_regression.wasm", (U512::from(MAX_PAYMENT),))
            .with_session_code("ee_601_regression.wasm", ())
            .with_authorization_keys(&[genesis_public_key])
            .build();

        ExecuteRequestBuilder::new().push_deploy(deploy).build()
    };

    let mut builder = InMemoryWasmTestBuilder::default();

    builder
        .run_genesis(&DEFAULT_GENESIS_CONFIG)
        .exec_with_exec_request(exec_request);

    let transforms = builder.get_transforms();
    let transform = &transforms[0];

    let add_keys = if let Some(Transform::AddKeys(keys)) =
        transform.get(&Key::Account(DEFAULT_ACCOUNT_ADDR))
    {
        keys
    } else {
        panic!(
            "expected AddKeys transform for given key but received {:?}",
            transforms[0]
        );
    };

    let pay_uref = add_keys
        .get("new_uref_result-payment")
        .expect("payment uref should exist");

    let session_uref = add_keys
        .get("new_uref_result-session")
        .expect("session uref should exist");

    assert_ne!(
        pay_uref, session_uref,
        "payment and session code should not create same uref"
    );

    builder.commit();

    let payment_value: Value = builder
        .query(None, *pay_uref, &[])
        .expect("should find payment value");

    assert_eq!(
        payment_value,
        Value::String("payment".to_string()),
        "expected payment"
    );

    let session_value: Value = builder
        .query(None, *session_uref, &[])
        .expect("should find session value");

    assert_eq!(
        session_value,
        Value::String("session".to_string()),
        "expected session"
    );
}
