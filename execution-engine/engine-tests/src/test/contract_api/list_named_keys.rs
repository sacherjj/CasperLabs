use std::collections::BTreeMap;

use engine_test_support::{
    internal::{ExecuteRequestBuilder, InMemoryWasmTestBuilder, DEFAULT_RUN_GENESIS_REQUEST},
    DEFAULT_ACCOUNT_ADDR,
};
use types::{account::PublicKey, runtime_args, Key, RuntimeArgs};

const CONTRACT_LIST_NAMED_KEYS: &str = "list_named_keys.wasm";
const NEW_NAME_ACCOUNT: &str = "Account";
const NEW_NAME_HASH: &str = "Hash";
const ARG_INITIAL_NAMED_KEYS: &str = "initial_named_args";
const ARG_NEW_NAMED_KEYS: &str = "new_named_keys";

#[ignore]
#[test]
fn should_list_named_keys() {
    let mut builder = InMemoryWasmTestBuilder::default();
    builder.run_genesis(&DEFAULT_RUN_GENESIS_REQUEST);

    let initial_named_keys: BTreeMap<String, Key> = BTreeMap::new();

    let new_named_keys = {
        let public_key = PublicKey::ed25519_from([1; 32]);
        let mut named_keys = BTreeMap::new();
        assert!(named_keys
            .insert(NEW_NAME_ACCOUNT.to_string(), Key::Account(public_key))
            .is_none());
        assert!(named_keys
            .insert(NEW_NAME_HASH.to_string(), Key::Hash([2; 32]))
            .is_none());
        named_keys
    };

    let exec_request = ExecuteRequestBuilder::standard(
        DEFAULT_ACCOUNT_ADDR,
        CONTRACT_LIST_NAMED_KEYS,
        runtime_args! {
            ARG_INITIAL_NAMED_KEYS => initial_named_keys,
            ARG_NEW_NAMED_KEYS => new_named_keys,
        },
    )
    .build();

    builder.exec(exec_request).commit().expect_success();
}
