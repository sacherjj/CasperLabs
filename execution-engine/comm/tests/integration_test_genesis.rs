extern crate grpc;

extern crate execution_engine;
extern crate shared;
extern crate storage;

extern crate casperlabs_engine_grpc_server;

#[allow(unused)]
mod test_support;

use grpc::RequestOptions;

use execution_engine::engine_state::EngineState;
use storage::global_state::in_memory::InMemoryGlobalState;

use casperlabs_engine_grpc_server::engine_server::ipc_grpc::ExecutionEngineService;

#[test]
fn should_run_genesis() {
    let global_state = InMemoryGlobalState::empty().expect("should create global state");
    let engine_state = EngineState::new(global_state, false);

    let genesis_request = test_support::create_genesis_request();

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
fn should_run_genesis_with_mint_bytes() {
    let global_state = InMemoryGlobalState::empty().expect("should create global state");
    let engine_state = EngineState::new(global_state, false);

    let genesis_request = test_support::create_genesis_request();

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
