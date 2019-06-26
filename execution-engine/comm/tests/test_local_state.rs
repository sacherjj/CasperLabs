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

use casperlabs_engine_grpc_server::engine_server::ipc::ExecResponse;
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
pub struct WasmTestBuilder {
    genesis_addr: [u8; 32],
    exec_responses: Vec<ExecResponse>,
    genesis_hash: Option<Vec<u8>>,
    post_state_hash: Option<Vec<u8>>,
    engine_state: EngineState<InMemoryGlobalState>,
    /// Cached transform maps after subsequent successful runs
    /// i.e. transforms[0] is for first run() call etc.
    transforms: Vec<HashMap<common::key::Key, Transform>>,
}

impl Default for WasmTestBuilder {
    fn default() -> WasmTestBuilder {
        let mocked_account = mocked_account(MOCKED_ACCOUNT_ADDRESS);
        let correlation_id = CorrelationId::new();
        let global_state =
            InMemoryGlobalState::from_pairs(correlation_id, &mocked_account).unwrap();
        let engine_state = EngineState::new(global_state, false);
        WasmTestBuilder {
            genesis_addr: [0; 32],
            exec_responses: Vec::new(),
            genesis_hash: None,
            post_state_hash: None,
            engine_state,
            transforms: Vec::new(),
        }
    }
}

impl WasmTestBuilder {
    /// Sets a genesis address
    pub fn with_genesis_addr(&mut self, genesis_addr: [u8; 32]) -> &mut WasmTestBuilder {
        self.genesis_addr = genesis_addr;
        self
    }

    pub fn run_genesis(&mut self) -> &mut WasmTestBuilder {
        let (genesis_request, _contracts) = create_genesis_request(self.genesis_addr);

        let genesis_response = self
            .engine_state
            .run_genesis(RequestOptions::new(), genesis_request)
            .wait_drop_metadata()
            .unwrap();

        let state_handle = self.engine_state.state();

        let state_root_hash = {
            let state_handle_guard = state_handle.lock();
            state_handle_guard.root_hash
        };

        let genesis_hash = genesis_response.get_success().get_poststate_hash().to_vec();
        assert_eq!(state_root_hash.to_vec(), genesis_hash);
        self.genesis_hash = Some(genesis_hash.clone());
        // This value will change between subsequent contract executions
        self.post_state_hash = Some(genesis_hash);
        self
    }

    /// Runs a contract and after that runs actual WASM contract and expects
    /// transformations to happen at the end of execution.
    pub fn exec(&mut self, wasm_file: &str) -> &mut WasmTestBuilder {
        let exec_request = create_exec_request(
            self.genesis_addr,
            &wasm_file,
            self.post_state_hash
                .as_ref()
                .expect("Should have post state hash"),
        );

        let exec_response = self
            .engine_state
            .exec(RequestOptions::new(), exec_request)
            .wait_drop_metadata()
            .expect("should exec");
        self.exec_responses.push(exec_response.clone());
        // Parse deploy results
        let deploy_result = exec_response
            .get_success()
            .get_deploy_results()
            .get(0)
            .expect("Unable to get first deploy result");
        let commit_transforms: CommitTransforms = deploy_result
            .get_execution_result()
            .get_effects()
            .get_transform_map()
            .try_into()
            .expect("should convert");
        let transforms = commit_transforms.value();
        // Cache transformations
        self.transforms.push(transforms);
        self
    }

    /// Runs a commit request, expects a successful response, and
    /// overwrites existing cached post state hash with a new one.
    pub fn commit(&mut self) -> &mut WasmTestBuilder {
        let commit_request = test_support::create_commit_request(
            self.post_state_hash
                .as_ref()
                .expect("Should have genesis hash"),
            self.transforms
                .last()
                .as_ref()
                .expect("Should have transform effects"),
        );

        let commit_response = self
            .engine_state
            .commit(RequestOptions::new(), commit_request)
            .wait_drop_metadata()
            .expect("Should have commit response");
        if !commit_response.has_success() {
            panic!(
                "Expected commit success but received a failure instead: {:?}",
                commit_response
            );
        }
        self.post_state_hash = Some(commit_response.get_success().get_poststate_hash().to_vec());
        self
    }

    /// Expects a successful run and caches transformations
    pub fn expect_success(&mut self) -> &mut WasmTestBuilder {
        // Check first result, as only first result is interesting for a simple test
        let exec_response = self
            .exec_responses
            .last()
            .expect("Expected to be called after run()")
            .clone();
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
        self
    }

    /// Gets the transform map that's cached between runs
    pub fn get_transforms(&self) -> Vec<HashMap<common::key::Key, Transform>> {
        self.transforms.clone()
    }
}

#[ignore]
#[test]
fn should_run_local_state_contract() {
    // This test runs a contract that's after every call extends the same key with more data
    let transforms = WasmTestBuilder::default()
        .with_genesis_addr(GENESIS_ADDR)
        .run_genesis()
        .exec("local_state.wasm")
        .expect_success()
        .commit()
        .exec("local_state.wasm")
        .expect_success()
        .commit()
        .get_transforms();

    let expected_local_key = Key::local(GENESIS_ADDR, &[66u8; 32].to_bytes().unwrap());

    assert_eq!(transforms.len(), 2);
    assert_eq!(
        transforms
            .get(0)
            .expect("Should have at least one transform")
            .get(&expected_local_key)
            .expect("Should have expected local key"),
        &Transform::Write(Value::String(String::from("Hello, world!")))
    );

    assert_eq!(
        transforms
            .get(1)
            .expect("Should have at least two transform")
            .get(&expected_local_key)
            .expect("Should have expected local key"),
        &Transform::Write(Value::String(String::from("Hello, world! Hello, world!")))
    );
}
