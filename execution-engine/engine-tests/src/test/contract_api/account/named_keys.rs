use engine_shared::{stored_value::StoredValue, transform::Transform};
use engine_test_support::low_level::{
    ExecuteRequestBuilder, InMemoryWasmTestBuilder, DEFAULT_ACCOUNT_ADDR, DEFAULT_GENESIS_CONFIG,
};
use types::{Key, U512};

const CONTRACT_NAMED_KEYS: &str = "named_keys.wasm";
const EXPECTED_UREF_VALUE: u64 = 123_456_789u64;

#[ignore]
#[test]
fn should_run_named_keys_contract() {
    let exec_request =
        ExecuteRequestBuilder::standard(DEFAULT_ACCOUNT_ADDR, CONTRACT_NAMED_KEYS, ()).build();

    let result = InMemoryWasmTestBuilder::default()
        .run_genesis(&DEFAULT_GENESIS_CONFIG)
        .exec(exec_request)
        .commit()
        .expect_success()
        .finish();

    let transforms = result.builder().get_transforms();

    assert_eq!(transforms.len(), 1);

    let transform = transforms
        .get(0)
        .expect("Should have at least one transform");

    let string_value = transform
        .iter()
        .filter_map(|(k, v)| {
            if let Transform::Write(StoredValue::CLValue(cl_value)) = v {
                let s = cl_value.to_owned().into_t::<String>().ok()?;
                if let Key::URef(_) = k {
                    return Some(s);
                }
            }
            None
        })
        .next()
        .expect("Should have write string");
    assert_eq!(string_value, "Hello, world!");
    let u512_value = transform
        .iter()
        .filter_map(|(k, v)| {
            if let Transform::Write(StoredValue::CLValue(cl_value)) = v {
                let value = cl_value.to_owned().into_t::<U512>().ok()?;
                if let Key::URef(_) = k {
                    // Since payment code is enabled by default there are multiple writes of Uint512
                    // type, so we narrow it down to the expected value.
                    if value == U512::from(EXPECTED_UREF_VALUE) {
                        return Some(());
                    }
                }
            }
            None
        })
        .next();

    assert!(u512_value.is_some(), "should have write uin512");

    let account = result
        .builder()
        .get_account(DEFAULT_ACCOUNT_ADDR)
        .expect("Unable to get account transformation");
    // Those named URefs are created, although removed at the end of the test
    assert!(account.named_keys().get("URef1").is_none());
    assert!(account.named_keys().get("URef2").is_none());
}
