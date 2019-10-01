use crate::support::test_support::{
    DeployItemBuilder, ExecuteRequestBuilder, InMemoryWasmTestBuilder, STANDARD_PAYMENT_CONTRACT,
};
use crate::test::{DEFAULT_ACCOUNT_ADDR, DEFAULT_GENESIS_CONFIG};
use contract_ffi::value::account::PublicKey;
use contract_ffi::value::U512;

use engine_core::engine_state::MAX_PAYMENT;
use engine_storage::global_state::in_memory::InMemoryGlobalState;

#[ignore]
#[test]
fn regression_test_genesis_hash_mismatch() {
    let mut builder_base = InMemoryWasmTestBuilder::default();

    let exec_request_1 = {
        let deploy = DeployItemBuilder::new()
            .with_address(DEFAULT_ACCOUNT_ADDR)
            .with_payment_code(STANDARD_PAYMENT_CONTRACT, (U512::from(MAX_PAYMENT),))
            .with_session_code("local_state.wasm", ())
            .with_deploy_hash([1u8; 32])
            .with_authorization_keys(&[PublicKey::new(DEFAULT_ACCOUNT_ADDR)])
            .build();
        ExecuteRequestBuilder::from_deploy_item(deploy).build()
    };

    // Step 1.
    let builder = builder_base.run_genesis(&DEFAULT_GENESIS_CONFIG);

    // This is trie's post state hash after calling run_genesis endpoint.
    // Step 1a)
    let genesis_run_hash = builder.get_genesis_hash();
    let genesis_transforms = builder.get_genesis_transforms().clone();

    let empty_root_hash = {
        let gs = InMemoryGlobalState::empty().expect("Empty GlobalState.");
        gs.empty_root_hash
    };

    // This is trie's post state hash after committing genesis effects on top of
    // empty trie. Step 1b)
    let genesis_transforms_hash = builder
        .commit_effects(empty_root_hash.to_vec(), genesis_transforms)
        .get_post_state_hash();

    // They should match.
    assert_eq!(genesis_run_hash, genesis_transforms_hash);

    // Step 2.
    builder
        .exec_with_exec_request(exec_request_1)
        .commit()
        .expect_success();

    // No step 3.
    // Step 4.
    builder.run_genesis(&DEFAULT_GENESIS_CONFIG);

    // Step 4a)
    let second_genesis_run_hash = builder.get_genesis_hash();
    let second_genesis_transforms = builder.get_genesis_transforms().clone();

    // Step 4b)
    let second_genesis_transforms_hash = builder
        .commit_effects(empty_root_hash.to_vec(), second_genesis_transforms)
        .get_post_state_hash();

    assert_eq!(second_genesis_run_hash, second_genesis_transforms_hash);

    assert_eq!(second_genesis_run_hash, genesis_run_hash);
    assert_eq!(second_genesis_transforms_hash, genesis_transforms_hash);
}
