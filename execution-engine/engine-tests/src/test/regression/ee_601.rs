use engine_shared::{stored_value::StoredValue, transform::Transform};
use engine_test_support::low_level::{
    DeployItemBuilder, ExecuteRequestBuilder, InMemoryWasmTestBuilder, DEFAULT_ACCOUNT_ADDR,
    DEFAULT_GENESIS_CONFIG, DEFAULT_PAYMENT,
};
use types::{account::PublicKey, CLValue, Key};

#[ignore]
#[test]
fn should_run_ee_601_pay_session_new_uref_collision() {
    let genesis_public_key = PublicKey::new(DEFAULT_ACCOUNT_ADDR);

    let exec_request = {
        let deploy = DeployItemBuilder::new()
            .with_deploy_hash([1; 32])
            .with_address(DEFAULT_ACCOUNT_ADDR)
            .with_payment_code("ee_601_regression.wasm", (*DEFAULT_PAYMENT,))
            .with_session_code("ee_601_regression.wasm", ())
            .with_authorization_keys(&[genesis_public_key])
            .build();

        ExecuteRequestBuilder::new().push_deploy(deploy).build()
    };

    let mut builder = InMemoryWasmTestBuilder::default();

    builder
        .run_genesis(&DEFAULT_GENESIS_CONFIG)
        .exec(exec_request);

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

    let payment_value: StoredValue = builder
        .query(None, *pay_uref, &[])
        .expect("should find payment value");

    assert_eq!(
        payment_value,
        StoredValue::CLValue(CLValue::from_t("payment".to_string()).unwrap()),
        "expected payment"
    );

    let session_value: StoredValue = builder
        .query(None, *session_uref, &[])
        .expect("should find session value");

    assert_eq!(
        session_value,
        StoredValue::CLValue(CLValue::from_t("session".to_string()).unwrap()),
        "expected session"
    );
}
