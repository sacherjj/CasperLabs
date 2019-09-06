use std::collections::HashMap;

use crate::support::test_support::{
    get_account, DeployBuilder, ExecRequestBuilder, WasmTestBuilder,
};
use contract_ffi::key::Key;
use contract_ffi::value::account::PublicKey;
use contract_ffi::value::{Value, U512};
use engine_core::engine_state::{EngineConfig, MAX_PAYMENT};

const GENESIS_ADDR: [u8; 32] = [6u8; 32];

#[ignore]
#[test]
fn should_run_ee_601_pay_session_new_uref_collision() {
    let genesis_public_key = PublicKey::new(GENESIS_ADDR);

    let engine_config = EngineConfig::new().set_use_payment_code(true);

    let exec_request = {
        let deploy = DeployBuilder::new()
            .with_address(GENESIS_ADDR)
            .with_payment_code("ee_601_regression.wasm", (U512::from(MAX_PAYMENT),))
            .with_session_code("ee_601_regression.wasm", ())
            .with_authorization_keys(&[genesis_public_key])
            .with_nonce(1)
            .build();

        ExecRequestBuilder::new().push_deploy(deploy).build()
    };

    let mut builder = WasmTestBuilder::new(engine_config);

    builder
        .run_genesis(GENESIS_ADDR, HashMap::default())
        .exec_with_exec_request(exec_request);

    let transforms = builder.get_transforms();

    let account =
        get_account(&transforms[0], &Key::Account(GENESIS_ADDR)).expect("account expected");

    let pay_uref = account
        .urefs_lookup()
        .get("new_uref_result-payment")
        .expect("payment uref should exist");

    let session_uref = account
        .urefs_lookup()
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
