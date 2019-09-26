use contract_ffi::value::account::PublicKey;
use contract_ffi::value::{Value, U512};
use engine_core::engine_state::{EngineConfig, MAX_PAYMENT};
use engine_shared::transform::Transform;

use crate::support::test_support::{DeployBuilder, ExecRequestBuilder, InMemoryWasmTestBuilder};
use crate::test::{DEFAULT_ACCOUNT_ADDR, DEFAULT_GENESIS_CONFIG};

#[ignore]
#[test]
fn should_run_ee_584_no_errored_session_transforms() {
    let genesis_public_key = PublicKey::new(DEFAULT_ACCOUNT_ADDR);

    let engine_config = EngineConfig::new().set_use_payment_code(true);

    let exec_request = {
        let deploy = DeployBuilder::new()
            .with_address(DEFAULT_ACCOUNT_ADDR)
            .with_session_code("ee_584_regression.wasm", ())
            .with_payment_code("standard_payment.wasm", (U512::from(MAX_PAYMENT),))
            .with_authorization_keys(&[genesis_public_key])
            .with_deploy_hash([1u8; 32])
            .build();

        ExecRequestBuilder::new().push_deploy(deploy).build()
    };

    let mut builder = InMemoryWasmTestBuilder::new(engine_config);

    builder
        .run_genesis(&DEFAULT_GENESIS_CONFIG)
        .exec_with_exec_request(exec_request);

    assert!(builder.is_error());

    let transforms = builder.get_transforms();

    assert!(transforms[0]
        .iter()
        .find(|(_, t)| if let Transform::Write(Value::String(s)) = t {
            s == "Hello, World!"
        } else {
            false
        })
        .is_none());
}
