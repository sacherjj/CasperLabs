extern crate grpc;

extern crate casperlabs_engine_grpc_server;
extern crate common;
extern crate execution_engine;
extern crate shared;
extern crate storage;

#[allow(dead_code)]
mod test_support;

use std::collections::HashMap;
use std::convert::TryInto;

use grpc::RequestOptions;

use casperlabs_engine_grpc_server::engine_server::ipc::{ExecResponse, ExecutionEffect};
use casperlabs_engine_grpc_server::engine_server::ipc_grpc::ExecutionEngineService;
use casperlabs_engine_grpc_server::engine_server::mappings::CommitTransforms;
use common::bytesrepr::ToBytes;
use common::key::Key;
use common::value::Value;
use execution_engine::engine_state::EngineState;
use shared::init::mocked_account;
use shared::newtypes::CorrelationId;
use shared::transform::Transform;
use storage::global_state::in_memory::InMemoryGlobalState;

use test_support::MOCKED_ACCOUNT_ADDRESS;
use test_support::{create_exec_request, create_genesis_request};

const GENESIS_ADDR: [u8; 32] = [6u8; 32];

/// Builder for simple WASM test
#[derive(Default)]
pub struct WasmTestBuilder {
    genesis_addr: [u8; 32],
    wasm_file: String,
    exec_response: Option<ExecResponse>,
}

impl WasmTestBuilder {
    pub fn new<T: Into<String>>(wasm_file: T) -> WasmTestBuilder {
        WasmTestBuilder {
            genesis_addr: [0; 32],
            wasm_file: wasm_file.into(),
            exec_response: None,
        }
    }
    /// Sets a genesis address
    pub fn with_genesis_addr(&mut self, genesis_addr: [u8; 32]) -> &mut WasmTestBuilder {
        self.genesis_addr = genesis_addr;
        self
    }

    /// Runs genesis and after that runs actual WASM contract and expects
    /// transformations to happen at the end of execution.
    pub fn run(&mut self) -> &mut WasmTestBuilder {
        let correlation_id = CorrelationId::new();
        let mocked_account = mocked_account(MOCKED_ACCOUNT_ADDRESS);
        let global_state =
            InMemoryGlobalState::from_pairs(correlation_id, &mocked_account).unwrap();
        let engine_state = EngineState::new(global_state, false);

        let (genesis_request, _contracts) = create_genesis_request(self.genesis_addr);

        let genesis_response = engine_state
            .run_genesis(RequestOptions::new(), genesis_request)
            .wait_drop_metadata()
            .unwrap();

        let state_handle = engine_state.state();

        let state_root_hash = {
            let state_handle_guard = state_handle.lock();
            let root_hash = state_handle_guard.root_hash;

            root_hash
        };

        let genesis_hash = genesis_response.get_success().get_poststate_hash();

        let post_state_hash = genesis_hash.to_vec();

        assert_eq!(state_root_hash.to_vec(), post_state_hash);
        let exec_request =
            create_exec_request(self.genesis_addr, &self.wasm_file, &post_state_hash);

        let exec_response = engine_state
            .exec(RequestOptions::new(), exec_request)
            .wait_drop_metadata()
            .expect("should exec");

        // Verify transforms
        self.exec_response = Some(exec_response.clone());
        self
    }

    /// Expects a successful run and returns transformations
    pub fn expect_success(&self) -> HashMap<common::key::Key, Transform> {
        // Check first result, as only first result is interesting for a simple test
        let exec_response = self
            .exec_response
            .as_ref()
            .expect("Expected to be called after run()");
        let deploy_result = exec_response
            .get_success()
            .get_deploy_results()
            .get(0)
            .expect("Unable to get first deploy result");
        if deploy_result.get_execution_result().has_error() {
            panic!(
                "Expected error, but instead got a successful response: {:?}",
                exec_response,
            );
        }
        let commit_transforms: CommitTransforms = deploy_result
            .get_execution_result()
            .get_effects()
            .get_transform_map()
            .try_into()
            .expect("should convert");
        commit_transforms.value()
    }
}

#[ignore]
#[test]
fn should_run_local_state_contract() {
    let transforms = WasmTestBuilder::new("local_state.wasm")
        .with_genesis_addr(GENESIS_ADDR)
        .run()
        .expect_success();

    let expected_local_key = Key::local(GENESIS_ADDR, &[66u8; 32].to_bytes().unwrap());

    assert_eq!(
        transforms
            .get(&expected_local_key)
            .expect("Should have expected local key"),
        &Transform::Write(Value::String(String::from("Hello, world!")))
    );
}
