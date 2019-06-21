extern crate grpc;

extern crate common;
extern crate execution_engine;
extern crate shared;
extern crate storage;

extern crate casperlabs_engine_grpc_server;

#[allow(unused)]
mod test_support;

use std::convert::TryInto;

use grpc::RequestOptions;

use common::value::Value;
use execution_engine::engine_state::EngineState;
use shared::transform::Transform;
use storage::global_state::in_memory::InMemoryGlobalState;

use casperlabs_engine_grpc_server::engine_server::ipc::ExecutionEffect;
use casperlabs_engine_grpc_server::engine_server::ipc_grpc::ExecutionEngineService;
use casperlabs_engine_grpc_server::engine_server::mappings::CommitTransforms;

#[ignore]
#[test]
fn should_transfer_to_account() {
    let global_state = InMemoryGlobalState::empty().unwrap();
    let engine_state = EngineState::new(global_state, false);

    let genesis_request = test_support::create_genesis_request();

    let genesis_response = engine_state
        .run_genesis(RequestOptions::new(), genesis_request)
        .wait_drop_metadata()
        .unwrap();

    let post_state_hash = genesis_response.get_success().get_poststate_hash().to_vec();

    println!("GenesisResponse: coming soon");

    {
        let execution_effect: &ExecutionEffect = genesis_response.get_success().get_effect();

        let commit_transforms: CommitTransforms = execution_effect
            .get_transform_map()
            .try_into()
            .expect("should convert");

        for (k, v) in commit_transforms.value().iter() {
            match v {
                Transform::Write(Value::Contract(_)) => {
                    println!("k: {:?}, v: contract", k);
                }
                Transform::Write(v) => {
                    println!("k: {:?}, v: {:?}", k, v);
                }
                _ => (),
            }
        }
    }

    let exec_request =
        test_support::create_exec_request("transfer_to_account.wasm", post_state_hash);

    let exec_response = engine_state
        .exec(RequestOptions::new(), exec_request)
        .wait_drop_metadata()
        .unwrap();

    println!("ExecResponse: {:?}", exec_response);

    {
        for result in exec_response.get_success().get_deploy_results() {
            let effects = result.get_execution_result().get_effects();

            let commit_transforms: CommitTransforms = effects
                .get_transform_map()
                .try_into()
                .expect("should convert");

            for (k, v) in commit_transforms.value().iter() {
                match v {
                    Transform::Write(Value::Contract(_)) => {
                        println!("k: {:?}, v: contract", k);
                    }
                    Transform::Write(v) => {
                        println!("k: {:?}, v: {:?}", k, v);
                    }
                    _ => (),
                }
            }
        }
    }

    // // Some stuff that will be helpful later:
    // let state_handle = engine_state.state();
    // let state_root_hash = {
    //     let state_handle_guard = state_handle.lock();
    //     let root_hash = state_handle_guard.root_hash;
    //     let mut tracking_copy: TrackingCopy<InMemoryGlobalState> = state_handle_guard
    //         .checkout(root_hash)
    //         .expect("should return global state")
    //         .map(TrackingCopy::new)
    //         .expect("should return tracking copy");
    //     root_hash
    // };
}
