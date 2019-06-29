extern crate grpc;

extern crate common;
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

const GENESIS_ADDR: [u8; 32] = [0u8; 32];

#[ignore]
#[test]
fn should_execute_contracts_which_provide_extra_urefs() {
    let global_state = InMemoryGlobalState::empty().unwrap();
    let engine_state = EngineState::new(global_state, false);

    // run genesis

    let (genesis_request, _) = test_support::create_genesis_request(GENESIS_ADDR);

    let genesis_response = engine_state
        .run_genesis(RequestOptions::new(), genesis_request)
        .wait_drop_metadata()
        .unwrap();

    let genesis_hash = genesis_response.get_success().get_poststate_hash();

    // exec 1

    let exec_request =
        test_support::create_exec_request(GENESIS_ADDR, "ee_401_regression.wasm", genesis_hash);

    let exec_response = engine_state
        .exec(RequestOptions::new(), exec_request)
        .wait_drop_metadata()
        .unwrap();

    let exec_transforms = &test_support::get_exec_transforms(&exec_response)[0];

    // commit 1

    let commit_request = test_support::create_commit_request(genesis_hash, &exec_transforms);

    let commit_response = engine_state
        .commit(RequestOptions::new(), commit_request)
        .wait_drop_metadata()
        .unwrap();

    let commit_hash = commit_response.get_success().get_poststate_hash();

    // exec 2

    let exec_request =
        test_support::create_exec_request(GENESIS_ADDR, "ee_401_regression_call.wasm", commit_hash);

    let exec_response = engine_state
        .exec(RequestOptions::new(), exec_request)
        .wait_drop_metadata()
        .unwrap();

    let deploy_failed = exec_response
        .get_success()
        .get_deploy_results()
        .get(0)
        .expect("should have deploy result")
        .get_execution_result()
        .has_error();

    assert!(!deploy_failed, "expected success");
}
