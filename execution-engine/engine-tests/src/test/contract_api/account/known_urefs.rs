use std::collections::HashMap;

use contract_ffi::key::Key;
use contract_ffi::value::{Value, U512};
use engine_shared::transform::Transform;

use crate::support::test_support::{WasmTestBuilder, DEFAULT_BLOCK_TIME};

const GENESIS_ADDR: [u8; 32] = [7u8; 32];

#[ignore]
#[test]
fn should_run_known_urefs_contract() {
    let result = WasmTestBuilder::default()
        .run_genesis(GENESIS_ADDR, HashMap::new())
        .exec(GENESIS_ADDR, "known_urefs.wasm", DEFAULT_BLOCK_TIME, 1)
        .commit()
        .expect_success()
        .finish();

    let transforms = result.builder().get_transforms();

    assert_eq!(transforms.len(), 1);

    let transform = transforms
        .get(0)
        .expect("Should have at least one transform");
    // Execution yields 3 transformations 2 of which are uref
    assert_eq!(transform.len(), 3);
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
                    return Some(value);
                }
            }
            None
        })
        .nth(0)
        .expect("Should have write string");

    assert_eq!(u512_value, &U512::from(123_456_789u64));

    let account = transform
        .get(&Key::Account(GENESIS_ADDR))
        .and_then(|transform| {
            if let Transform::Write(Value::Account(account)) = transform {
                Some(account)
            } else {
                None
            }
        })
        .expect("Unable to get account transformation");
    // Those named URefs are created, although removed at the end of the test
    assert!(account.urefs_lookup().get("URef1").is_none());
    assert!(account.urefs_lookup().get("URef2").is_none());
}
