use std::collections::HashMap;

use contract_ffi::bytesrepr::ToBytes;
use contract_ffi::key::Key;
use contract_ffi::value::Value;
use engine_shared::transform::Transform;

use crate::support::test_support::{WasmTestBuilder, DEFAULT_BLOCK_TIME};

const GENESIS_ADDR: [u8; 32] = [6u8; 32];

#[ignore]
#[test]
fn should_run_local_state_contract() {
    // This test runs a contract that's after every call extends the same key with
    // more data
    let result = WasmTestBuilder::default()
        .run_genesis(GENESIS_ADDR, HashMap::new())
        .exec(GENESIS_ADDR, "local_state.wasm", DEFAULT_BLOCK_TIME, 1)
        .expect_success()
        .commit()
        .exec(GENESIS_ADDR, "local_state.wasm", DEFAULT_BLOCK_TIME, 2)
        .expect_success()
        .commit()
        .finish();

    let transforms = result.builder().get_transforms();

    let expected_local_key = Key::local(GENESIS_ADDR, &[66u8; 32].to_bytes().unwrap());

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
