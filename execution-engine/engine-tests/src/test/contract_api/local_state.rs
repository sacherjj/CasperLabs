use contract_ffi::bytesrepr::ToBytes;
use contract_ffi::key::Key;
use contract_ffi::value::Value;
use engine_shared::transform::Transform;

use crate::support::test_support::{InMemoryWasmTestBuilder, DEFAULT_BLOCK_TIME};
use crate::test::{DEFAULT_ACCOUNT_ADDR, DEFAULT_GENESIS_CONFIG};

#[ignore]
#[test]
fn should_run_local_state_contract() {
    // This test runs a contract that's after every call extends the same key with
    // more data
    let result = InMemoryWasmTestBuilder::default()
        .run_genesis(&DEFAULT_GENESIS_CONFIG)
        .exec(
            DEFAULT_ACCOUNT_ADDR,
            "local_state.wasm",
            DEFAULT_BLOCK_TIME,
            [1u8; 32],
        )
        .expect_success()
        .commit()
        .exec(
            DEFAULT_ACCOUNT_ADDR,
            "local_state.wasm",
            DEFAULT_BLOCK_TIME,
            [2u8; 32],
        )
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
