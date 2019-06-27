extern crate grpc;

extern crate casperlabs_engine_grpc_server;
extern crate common;
extern crate execution_engine;
extern crate shared;
extern crate storage;

#[allow(dead_code)]
mod test_support;

use common::bytesrepr::ToBytes;
use common::key::Key;
use common::value::Value;
use shared::transform::Transform;

use test_support::WasmTestBuilder;

const GENESIS_ADDR: [u8; 32] = [6u8; 32];

#[ignore]
#[test]
fn should_run_local_state_contract() {
    // This test runs a contract that's after every call extends the same key with more data
    let transforms = WasmTestBuilder::new(GENESIS_ADDR)
        .run_genesis()
        .exec("local_state.wasm")
        .expect_success()
        .commit()
        .exec("local_state.wasm")
        .expect_success()
        .commit()
        .get_transforms();

    let expected_local_key = Key::local(GENESIS_ADDR, &[66u8; 32].to_bytes().unwrap());

    assert_eq!(transforms.len(), 2);
    assert_eq!(
        transforms
            .get(0)
            .expect("Should have at least one transform")
            .get(&expected_local_key)
            .expect("Should have expected local key"),
        &Transform::Write(Value::String(String::from("Hello, world!")))
    );

    assert_eq!(
        transforms
            .get(1)
            .expect("Should have at least two transform")
            .get(&expected_local_key)
            .expect("Should have expected local key"),
        &Transform::Write(Value::String(String::from("Hello, world! Hello, world!")))
    );
}
