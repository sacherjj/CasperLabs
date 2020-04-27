use std::convert::TryInto;

use engine_shared::{stored_value::StoredValue, transform::Transform};
use engine_test_support::{
    internal::{ExecuteRequestBuilder, InMemoryWasmTestBuilder, DEFAULT_RUN_GENESIS_REQUEST},
    DEFAULT_ACCOUNT_ADDR,
};
use types::{bytesrepr::ToBytes, CLValue, Key};

const CONTRACT_LOCAL_STATE: &str = "local_state.wasm";

const CONTRACT_LOCAL_STATE_ADD: &str = "local_state_add.wasm";
const CMD_WRITE: &str = "write";
const CMD_ADD: &str = "add";

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
        .run_genesis(&DEFAULT_RUN_GENESIS_REQUEST)
        .exec(exec_request_1)
        .expect_success()
        .commit()
        .exec(exec_request_2)
        .expect_success()
        .commit()
        .finish();

    let transforms = result.builder().get_transforms();

    let expected_local_key = Key::local(
        DEFAULT_ACCOUNT_ADDR
            .as_bytes()
            .try_into()
            .expect("should be 32 bytes"),
        &[66u8; 32].to_bytes().unwrap(),
    );

    assert_eq!(transforms.len(), 2);
    assert_eq!(
        transforms[0]
            .get(&expected_local_key)
            .expect("Should have expected local key transforms[0]"),
        &Transform::Write(StoredValue::CLValue(
            CLValue::from_t(String::from("Hello, world!")).unwrap()
        ))
    );

    assert_eq!(
        transforms[1]
            .get(&expected_local_key)
            .expect("Should have expected local key transforms[1]"),
        &Transform::Write(StoredValue::CLValue(
            CLValue::from_t(String::from("Hello, world! Hello, world!")).unwrap()
        ))
    );
}

#[ignore]
#[test]
fn should_add_to_local_state() {
    let exec_request_1 = ExecuteRequestBuilder::standard(
        DEFAULT_ACCOUNT_ADDR,
        CONTRACT_LOCAL_STATE_ADD,
        (CMD_WRITE,),
    )
    .build();

    let exec_request_2 =
        ExecuteRequestBuilder::standard(DEFAULT_ACCOUNT_ADDR, CONTRACT_LOCAL_STATE_ADD, (CMD_ADD,))
            .build();

    let result = InMemoryWasmTestBuilder::default()
        .run_genesis(&DEFAULT_RUN_GENESIS_REQUEST)
        .exec(exec_request_1)
        .expect_success()
        .commit()
        .exec(exec_request_2)
        .expect_success()
        .commit()
        .finish();

    let transforms = result.builder().get_transforms();

    let expected_local_key = Key::local(
        DEFAULT_ACCOUNT_ADDR
            .as_bytes()
            .try_into()
            .expect("should be 32 bytes"),
        &[66u8; 32].to_bytes().unwrap(),
    );

    assert_eq!(transforms.len(), 2);
    assert_eq!(
        transforms[0]
            .get(&expected_local_key)
            .expect("Should have expected local key"),
        &Transform::Write(StoredValue::CLValue(CLValue::from_t(10u64).unwrap())),
        "local key should have u64 10"
    );

    assert_eq!(
        transforms[1]
            .get(&expected_local_key)
            .expect("Should have expected local key"),
        &Transform::AddUInt64(5),
        "local key should have u64 5"
    );
}
