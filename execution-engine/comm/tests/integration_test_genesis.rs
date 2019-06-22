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

use casperlabs_engine_grpc_server::engine_server::ipc::{DeployCode, GenesisRequest};
use casperlabs_engine_grpc_server::engine_server::ipc_grpc::ExecutionEngineService;
use casperlabs_engine_grpc_server::engine_server::state::{BigInt, ProtocolVersion};
use shared::test_utils;

#[test]
fn should_run_genesis() {
    let global_state = InMemoryGlobalState::empty().expect("should create global state");
    let engine_state = EngineState::new(global_state, false);

    let genesis_request = {
        let genesis_account_addr = [6u8; 32].to_vec();

        let initial_tokens = {
            let mut ret = BigInt::new();
            ret.set_bit_width(512);
            ret.set_value("1000000".to_string());
            ret
        };

        let mint_code = {
            let mut ret = DeployCode::new();
            let wasm_bytes = test_utils::create_empty_wasm_module_bytes();
            ret.set_code(wasm_bytes);
            ret
        };

        let proof_of_stake_code = {
            let mut ret = DeployCode::new();
            let wasm_bytes = test_utils::create_empty_wasm_module_bytes();
            ret.set_code(wasm_bytes);
            ret
        };

        let protocol_version = {
            let mut ret = ProtocolVersion::new();
            ret.set_value(1);
            ret
        };

        let mut ret = GenesisRequest::new();
        ret.set_address(genesis_account_addr.to_vec());
        ret.set_initial_tokens(initial_tokens);
        ret.set_mint_code(mint_code);
        ret.set_proof_of_stake_code(proof_of_stake_code);
        ret.set_protocol_version(protocol_version);
        ret
    };

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
