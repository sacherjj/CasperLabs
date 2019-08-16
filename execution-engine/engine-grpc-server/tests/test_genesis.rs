extern crate casperlabs_engine_grpc_server;
extern crate engine_core;
extern crate engine_shared;
extern crate engine_storage;
extern crate grpc;

use std::collections::HashMap;

use grpc::RequestOptions;

use casperlabs_engine_grpc_server::engine_server::ipc_grpc::ExecutionEngineService;
use engine_core::engine_state::EngineState;
use engine_storage::global_state::in_memory::InMemoryGlobalState;

#[allow(unused)]
mod test_support;

const GENESIS_ADDR: [u8; 32] = [6u8; 32];

#[ignore]
#[test]
fn should_run_genesis() {
    let global_state = InMemoryGlobalState::empty().expect("should create global state");
    let engine_state = EngineState::new(global_state, Default::default());

    let (genesis_request, _) = test_support::create_genesis_request(GENESIS_ADDR, HashMap::new());

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
