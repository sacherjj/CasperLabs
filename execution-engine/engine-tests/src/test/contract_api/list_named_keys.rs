use std::collections::BTreeMap;

use contract::contract_api::system::{MINT_NAME, POS_NAME};
use engine_test_support::low_level::{
    ExecuteRequestBuilder, InMemoryWasmTestBuilder, DEFAULT_ACCOUNT_ADDR, DEFAULT_GENESIS_CONFIG,
};
use types::Key;

const CONTRACT_LIST_NAMED_KEYS: &str = "list_named_keys.wasm";
const NEW_NAME_ACCOUNT: &str = "Account";
const NEW_NAME_HASH: &str = "Hash";
const NEW_NAME_LOCAL: &str = "Local";

#[ignore]
#[test]
fn should_list_named_keys() {
    let mut builder = InMemoryWasmTestBuilder::default();
    builder.run_genesis(&DEFAULT_GENESIS_CONFIG);

    let mint_uref = builder.get_mint_contract_uref().into_read();
    let pos_uref = builder.get_pos_contract_uref().into_read();

    let initial_named_keys = {
        let mut named_keys = BTreeMap::new();
        assert!(named_keys
            .insert(MINT_NAME.to_string(), Key::URef(mint_uref))
            .is_none());
        assert!(named_keys
            .insert(POS_NAME.to_string(), Key::URef(pos_uref))
            .is_none());
        named_keys
    };

    let new_named_keys = {
        let mut named_keys = BTreeMap::new();
        assert!(named_keys
            .insert(NEW_NAME_ACCOUNT.to_string(), Key::Account([1; 32]))
            .is_none());
        assert!(named_keys
            .insert(NEW_NAME_HASH.to_string(), Key::Hash([2; 32]))
            .is_none());
        assert!(named_keys
            .insert(NEW_NAME_LOCAL.to_string(), Key::Local([3; 32]))
            .is_none());
        named_keys
    };

    let exec_request = ExecuteRequestBuilder::standard(
        DEFAULT_ACCOUNT_ADDR,
        CONTRACT_LIST_NAMED_KEYS,
        (initial_named_keys, new_named_keys),
    )
    .build();

    builder.exec(exec_request).commit().expect_success();
}
