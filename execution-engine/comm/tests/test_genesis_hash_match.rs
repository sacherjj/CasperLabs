extern crate casperlabs_engine_grpc_server;
extern crate common;
extern crate execution_engine;
extern crate grpc;
extern crate shared;
extern crate storage;

use std::collections::HashMap;

use storage::global_state::in_memory::InMemoryGlobalState;
use test_support::WasmTestBuilder;

#[allow(unused)]
mod test_support;

const GENESIS_ADDR: [u8; 32] = [12; 32];

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
