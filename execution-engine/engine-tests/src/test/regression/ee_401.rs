use std::collections::HashMap;

use grpc::RequestOptions;

use contract_ffi::value::account::PublicKey;
use engine_core::engine_state::EngineState;
use engine_grpc_server::engine_server::ipc_grpc::ExecutionEngineService;
use engine_storage::global_state::in_memory::InMemoryGlobalState;

use crate::support::test_support::DEFAULT_BLOCK_TIME;

const GENESIS_ADDR: [u8; 32] = [0u8; 32];

#[ignore]
#[test]
fn should_execute_contracts_which_provide_extra_urefs() {
    let global_state = InMemoryGlobalState::empty().unwrap();
    let engine_state = EngineState::new(global_state, Default::default());

    // run genesis

    let (genesis_request, _) =
        crate::support::test_support::create_genesis_request(GENESIS_ADDR, HashMap::new());

    let genesis_response = engine_state
        .run_genesis(RequestOptions::new(), genesis_request)
        .wait_drop_metadata()
        .unwrap();

    let genesis_hash = genesis_response.get_success().get_poststate_hash();

    // exec 1

    let exec_request = crate::support::test_support::create_exec_request(
        GENESIS_ADDR,
        "ee_401_regression.wasm",
        genesis_hash,
        DEFAULT_BLOCK_TIME,
        1,
        (),
        vec![PublicKey::new(GENESIS_ADDR)],
    );

    let exec_response = engine_state
        .exec(RequestOptions::new(), exec_request)
        .wait_drop_metadata()
        .unwrap();

    let exec_transforms = &crate::support::test_support::get_exec_transforms(&exec_response)[0];

    // commit 1

    let commit_request =
        crate::support::test_support::create_commit_request(genesis_hash, &exec_transforms);

    let commit_response = engine_state
        .commit(RequestOptions::new(), commit_request)
        .wait_drop_metadata()
        .unwrap();

    let commit_hash = commit_response.get_success().get_poststate_hash();

    // exec 2

    let exec_request = crate::support::test_support::create_exec_request(
        GENESIS_ADDR,
        "ee_401_regression_call.wasm",
        commit_hash,
        DEFAULT_BLOCK_TIME,
        1,
        (),
        vec![PublicKey::new(GENESIS_ADDR)],
    );

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
