use std::collections::HashMap;

use grpc::RequestOptions;

use crate::support::test_support::WasmTestBuilder;
use engine_core::engine_state::EngineState;
use engine_grpc_server::engine_server::ipc_grpc::ExecutionEngineService;
use engine_storage::global_state::in_memory::InMemoryGlobalState;

const GENESIS_ADDR: [u8; 32] = [6u8; 32];

#[ignore]
#[test]
fn should_run_genesis() {
    let global_state = InMemoryGlobalState::empty().expect("should create global state");
    let engine_state = EngineState::new(global_state, Default::default());

    let (genesis_request, _) =
        crate::support::test_support::create_genesis_request(GENESIS_ADDR, HashMap::new());

    let request_options = RequestOptions::new();

    let genesis_response = engine_state
        .run_genesis(request_options, genesis_request)
        .wait_drop_metadata();

    let response = genesis_response.unwrap();

    let state_handle = engine_state.state();

    let state_handle_guard = state_handle.lock();

    let state_root_hash = state_handle_guard.root_hash;
    let response_root_hash = response.get_success().get_poststate_hash();

    assert_eq!(state_root_hash.to_vec(), response_root_hash.to_vec());
}

#[ignore]
#[test]
fn test_genesis_hash_match() {
    let mut builder_base = WasmTestBuilder::default();

    let builder = builder_base.run_genesis(GENESIS_ADDR, HashMap::new());

    // This is trie's post state hash after calling run_genesis endpoint.
    let genesis_run_hash = builder.get_genesis_hash();
    let genesis_transforms = builder.get_genesis_transforms().clone();

    let empty_root_hash = {
        let gs = InMemoryGlobalState::empty().expect("Empty GlobalState.");
        gs.root_hash
    };

    // This is trie's post state hash after committing genesis effects on top of empty trie.
    let genesis_transforms_hash = builder
        .commit_effects(empty_root_hash.to_vec(), genesis_transforms)
        .get_poststate_hash();

    // They should match.
    assert_eq!(genesis_run_hash, genesis_transforms_hash);
}
