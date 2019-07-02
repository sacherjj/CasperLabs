extern crate casperlabs_engine_grpc_server;
extern crate common;
extern crate execution_engine;
extern crate grpc;
extern crate shared;
extern crate storage;

use common::bytesrepr::ToBytes;
use common::key::Key;
use common::value::Value;
use shared::transform::Transform;
use test_support::WasmTestBuilder;

#[allow(dead_code)]
mod test_support;

const GENESIS_ADDR: [u8; 32] = [6u8; 32];

#[ignore]
#[test]
fn should_run_local_state_contract() {
    // This test runs a contract that's after every call extends the same key with more data
    let transforms = WasmTestBuilder::default()
        .run_genesis(GENESIS_ADDR, Vec::new())
        .exec(GENESIS_ADDR, "local_state.wasm")
        .expect_success()
        .commit()
        .exec(GENESIS_ADDR, "local_state.wasm")
        .expect_success()
        .commit()
        .get_transforms();

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
