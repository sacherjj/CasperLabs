use contract_ffi::key::Key;
use contract_ffi::value::{Value, U512};
use engine_shared::transform::Transform;

use crate::test::{DEFAULT_ACCOUNT_ADDR, DEFAULT_GENESIS_CONFIG};

use crate::support::test_support::{ExecuteRequestBuilder, InMemoryWasmTestBuilder};

const CONTRACT_NAMED_KEYS: &str = "named_keys";
const EXPECTED_UREF_VALUE: u64 = 123_456_789u64;

#[ignore]
#[test]
fn should_run_named_keys_contract() {
    let exec_request = {
        let contract_name = format!("{}.wasm", CONTRACT_NAMED_KEYS);
        ExecuteRequestBuilder::standard(DEFAULT_ACCOUNT_ADDR, &contract_name, ())
    };

    let result = InMemoryWasmTestBuilder::default()
        .run_genesis(&DEFAULT_GENESIS_CONFIG)
        .exec_with_exec_request(exec_request)
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
            if let Transform::Write(Value::String(s)) = v {
                if let Key::URef(_) = k {
                    return Some(s);
                }
            }
            None
        })
        .nth(0)
        .expect("Should have write string");
    assert_eq!(string_value, "Hello, world!");
    let u512_value = transform
        .iter()
        .filter_map(|(k, v)| {
            if let Transform::Write(Value::UInt512(value)) = v {
                if let Key::URef(_) = k {
                    // Since payment code is enabled by default there are multiple writes of Uint512
                    // type, so we narrow it down to the expected value.
                    if value == &U512::from(EXPECTED_UREF_VALUE) {
                        return Some(());
                    }
                }
            }
            None
        })
        .nth(0);

    assert!(u512_value.is_some(), "should have write uin512");

    let account = result
        .builder()
        .get_account(DEFAULT_ACCOUNT_ADDR)
        .expect("Unable to get account transformation");
    // Those named URefs are created, although removed at the end of the test
    assert!(account.named_keys().get("URef1").is_none());
    assert!(account.named_keys().get("URef2").is_none());
}
