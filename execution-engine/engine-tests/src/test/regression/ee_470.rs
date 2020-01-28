use engine_storage::global_state::in_memory::InMemoryGlobalState;
use engine_test_support::{
    internal::{ExecuteRequestBuilder, InMemoryWasmTestBuilder, DEFAULT_GENESIS_CONFIG},
    DEFAULT_ACCOUNT_ADDR,
};

const CONTRACT_LOCAL_STATE: &str = "local_state.wasm";

#[ignore]
#[test]
fn regression_test_genesis_hash_mismatch() {
    let mut builder_base = InMemoryWasmTestBuilder::default();

    let exec_request_1 =
        ExecuteRequestBuilder::standard(DEFAULT_ACCOUNT_ADDR, CONTRACT_LOCAL_STATE, ()).build();

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
    builder.exec(exec_request_1).commit().expect_success();

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
