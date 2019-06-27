extern crate casperlabs_engine_grpc_server;
extern crate common;
extern crate execution_engine;
extern crate grpc;
extern crate shared;
extern crate storage;
extern crate wasm_prep;

use std::collections::HashMap;
use std::convert::TryInto;
use std::path::PathBuf;

use execution_engine::engine_state::utils::WasmiBytes;
use shared::test_utils;
use shared::transform::Transform;

use casperlabs_engine_grpc_server::engine_server::ipc::{
    CommitRequest, Deploy, DeployCode, DeployResult, ExecRequest, ExecResponse, GenesisRequest,
    GenesisResponse, TransformEntry,
};
use casperlabs_engine_grpc_server::engine_server::mappings::CommitTransforms;
use casperlabs_engine_grpc_server::engine_server::state::{BigInt, ProtocolVersion};

use casperlabs_engine_grpc_server::engine_server::ipc_grpc::ExecutionEngineService;
//use common::bytesrepr::ToBytes;
//use common::key::Key;
//use common::value::Value;

use execution_engine::engine_state::EngineState;
use grpc::RequestOptions;
use storage::global_state::in_memory::InMemoryGlobalState;

pub const MOCKED_ACCOUNT_ADDRESS: [u8; 32] = [48u8; 32];
pub const COMPILED_WASM_PATH: &str = "../target/wasm32-unknown-unknown/debug";

pub fn get_protocol_version() -> ProtocolVersion {
    let mut protocol_version: ProtocolVersion = ProtocolVersion::new();
    protocol_version.set_value(1);
    protocol_version
}

pub fn get_mock_deploy() -> Deploy {
    let mut deploy = Deploy::new();
    deploy.set_address(MOCKED_ACCOUNT_ADDRESS.to_vec());
    deploy.set_gas_limit(1000);
    deploy.set_gas_price(1);
    deploy.set_nonce(1);
    let mut deploy_code = DeployCode::new();
    deploy_code.set_code(test_utils::create_empty_wasm_module_bytes());
    deploy.set_session(deploy_code);
    deploy
}

fn get_compiled_wasm_path(contract_file: PathBuf) -> PathBuf {
    let mut path = std::env::current_dir().expect("should get working directory");
    path.push(PathBuf::from(COMPILED_WASM_PATH));
    path.push(contract_file);
    path
}

/// Reads a given compiled contract file from [`COMPILED_WASM_PATH`].
pub fn read_wasm_file_bytes(contract_file: &str) -> Vec<u8> {
    let contract_file = PathBuf::from(contract_file);
    let path = get_compiled_wasm_path(contract_file);
    std::fs::read(path.clone())
        .unwrap_or_else(|_| panic!("should read bytes from disk: {:?}", path))
}

#[derive(Debug, Copy, Clone, Hash, PartialEq, Eq)]
pub enum SystemContractType {
    Mint,
    ProofOfStake,
}

pub fn create_genesis_request(
    address: [u8; 32],
) -> (GenesisRequest, HashMap<SystemContractType, WasmiBytes>) {
    let genesis_account_addr = address.to_vec();
    let mut contracts: HashMap<SystemContractType, WasmiBytes> = HashMap::new();

    let initial_tokens = {
        let mut ret = BigInt::new();
        ret.set_bit_width(512);
        ret.set_value("1000000".to_string());
        ret
    };

    let mint_code = {
        let mut ret = DeployCode::new();
        let contract_file = "mint_token.wasm";
        let wasm_bytes = read_wasm_file_bytes(contract_file);
        let wasmi_bytes = WasmiBytes::new(&wasm_bytes, wasm_prep::wasm_costs::WasmCosts::free())
            .expect("should have wasmi bytes");
        contracts.insert(SystemContractType::Mint, wasmi_bytes);
        ret.set_code(wasm_bytes);
        ret
    };

    let proof_of_stake_code = {
        let mut ret = DeployCode::new();
        let wasm_bytes = test_utils::create_empty_wasm_module_bytes();
        let wasmi_bytes = WasmiBytes::new(&wasm_bytes, wasm_prep::wasm_costs::WasmCosts::free())
            .expect("should have wasmi bytes");
        contracts.insert(SystemContractType::ProofOfStake, wasmi_bytes);
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

    (ret, contracts)
}

pub fn create_exec_request(
    address: [u8; 32],
    contract_file_name: &str,
    pre_state_hash: &[u8],
) -> ExecRequest {
    let bytes_to_deploy = read_wasm_file_bytes(contract_file_name);

    let mut deploy = Deploy::new();
    deploy.set_address(address.to_vec());
    deploy.set_gas_limit(1_000_000_000);
    deploy.set_gas_price(1);
    deploy.set_nonce(1);
    let mut deploy_code = DeployCode::new();
    deploy_code.set_code(bytes_to_deploy);
    deploy.set_session(deploy_code);

    let mut exec_request = ExecRequest::new();
    let mut deploys: protobuf::RepeatedField<Deploy> = <protobuf::RepeatedField<Deploy>>::new();
    deploys.push(deploy);

    exec_request.set_deploys(deploys);
    exec_request.set_parent_state_hash(pre_state_hash.to_vec());
    exec_request.set_protocol_version(get_protocol_version());

    exec_request
}

#[allow(clippy::implicit_hasher)]
pub fn create_commit_request(
    prestate_hash: &[u8],
    effects: &HashMap<common::key::Key, Transform>,
) -> CommitRequest {
    let effects: Vec<TransformEntry> = effects
        .iter()
        .map(|(k, t)| (k.to_owned(), t.to_owned()).into())
        .collect();

    let mut commit_request = CommitRequest::new();
    commit_request.set_prestate_hash(prestate_hash.to_vec());
    commit_request.set_effects(effects.into());
    commit_request
}

#[allow(clippy::implicit_hasher)]
pub fn get_genesis_transforms(
    genesis_response: &GenesisResponse,
) -> HashMap<common::key::Key, Transform> {
    let commit_transforms: CommitTransforms = genesis_response
        .get_success()
        .get_effect()
        .get_transform_map()
        .try_into()
        .expect("should convert");
    commit_transforms.value()
}

pub fn get_exec_transforms(
    exec_response: &ExecResponse,
) -> Vec<HashMap<common::key::Key, Transform>> {
    let deploy_results: &[DeployResult] = exec_response.get_success().get_deploy_results();

    deploy_results
        .iter()
        .map(|deploy_result| {
            let commit_transforms: CommitTransforms = deploy_result
                .get_execution_result()
                .get_effects()
                .get_transform_map()
                .try_into()
                .expect("should convert");
            commit_transforms.value()
        })
        .collect()
}

#[allow(clippy::implicit_hasher)]
pub fn get_mint_contract_uref(
    transforms: &HashMap<common::key::Key, Transform>,
    contracts: &HashMap<SystemContractType, WasmiBytes>,
) -> Option<common::uref::URef> {
    let mint_contract_bytes: Vec<u8> = contracts
        .get(&SystemContractType::Mint)
        .map(ToOwned::to_owned)
        .map(Into::into)
        .expect("should get mint bytes");

    transforms
        .iter()
        .find(|(_, v)| match v {
            Transform::Write(common::value::Value::Contract(mint_contract))
                if mint_contract.bytes() == mint_contract_bytes.as_slice() =>
            {
                true
            }
            _ => false,
        })
        .and_then(|(k, _)| {
            if let common::key::Key::URef(uref) = k {
                Some(*uref)
            } else {
                None
            }
        })
}

#[allow(clippy::implicit_hasher)]
pub fn get_account(
    transforms: &HashMap<common::key::Key, Transform>,
    account: &common::key::Key,
) -> Option<common::value::Account> {
    transforms.get(account).and_then(|transform| {
        if let Transform::Write(common::value::Value::Account(account)) = transform {
            Some(account.to_owned())
        } else {
            None
        }
    })
}

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

impl WasmTestBuilder {
    /// Creates a builder with an address
    pub fn new(genesis_addr: [u8; 32]) -> WasmTestBuilder {
        let global_state = InMemoryGlobalState::empty().expect("should create global state");
        let engine_state = EngineState::new(global_state, false);
        WasmTestBuilder {
            genesis_addr,
            exec_responses: Vec::new(),
            genesis_hash: None,
            post_state_hash: None,
            engine_state,
            transforms: Vec::new(),
        }
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
        let commit_request = create_commit_request(
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
