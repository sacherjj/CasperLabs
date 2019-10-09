use contract_ffi::bytesrepr::ToBytes;
use contract_ffi::key::Key;
use contract_ffi::value::Value;
use engine_shared::transform::Transform;

use crate::support::test_support::{ExecuteRequestBuilder, InMemoryWasmTestBuilder};
use crate::test::{DEFAULT_ACCOUNT_ADDR, DEFAULT_GENESIS_CONFIG};
const CONTRACT_LOCAL_STATE: &str = "local_state.wasm";

#[ignore]
#[test]
fn should_run_local_state_contract() {
    let exec_request_1 =
        ExecuteRequestBuilder::standard(DEFAULT_ACCOUNT_ADDR, CONTRACT_LOCAL_STATE, ()).build();

    let exec_request_2 =
        ExecuteRequestBuilder::standard(DEFAULT_ACCOUNT_ADDR, CONTRACT_LOCAL_STATE, ()).build();

    // This test runs a contract that's after every call extends the same key with
    // more data
    let result = InMemoryWasmTestBuilder::default()
        .run_genesis(&DEFAULT_GENESIS_CONFIG)
        .exec(exec_request_1)
        .expect_success()
        .commit()
        .exec(exec_request_2)
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
