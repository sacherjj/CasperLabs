use std::collections::HashMap;
use std::convert::TryInto;
use std::ffi::OsStr;
use std::path::PathBuf;
use std::rc::Rc;
use std::sync::Arc;

use grpc::RequestOptions;
use lmdb::DatabaseFlags;
use rand::Rng;

use contract_ffi::bytesrepr::ToBytes;
use contract_ffi::contract_api::argsparser::ArgsParser;
use contract_ffi::key::Key;
use contract_ffi::uref::URef;
use contract_ffi::value::account::{Account, PublicKey, PurseId};
use contract_ffi::value::contract::Contract;
use contract_ffi::value::{Value, U512};
use engine_core::engine_state::genesis::{GenesisAccount, GenesisConfig};
use engine_core::engine_state::utils::WasmiBytes;
use engine_core::engine_state::{EngineConfig, EngineState, MAX_PAYMENT, SYSTEM_ACCOUNT_ADDR};
use engine_core::execution::{self, MINT_NAME, POS_NAME};
use engine_grpc_server::engine_server::ipc::{
    ChainSpec_ActivationPoint, ChainSpec_CostTable_WasmCosts, ChainSpec_UpgradePoint,
    CommitRequest, DeployCode, DeployItem, DeployPayload, DeployResult,
    DeployResult_ExecutionResult, DeployResult_PreconditionFailure, ExecuteRequest,
    ExecuteResponse, GenesisResponse, QueryRequest, StoredContractHash, StoredContractName,
    StoredContractURef, UpgradeRequest, UpgradeResponse,
};
use engine_grpc_server::engine_server::ipc_grpc::ExecutionEngineService;
use engine_grpc_server::engine_server::mappings::{CommitTransforms, MappingError};
use engine_grpc_server::engine_server::state::ProtocolVersion;
use engine_grpc_server::engine_server::transforms;
use engine_shared::gas::Gas;
use engine_shared::newtypes::Blake2bHash;
use engine_shared::os::get_page_size;
use engine_shared::test_utils;
use engine_shared::transform::Transform;
use engine_storage::global_state::in_memory::InMemoryGlobalState;
use engine_storage::global_state::lmdb::LmdbGlobalState;
use engine_storage::global_state::StateProvider;
use engine_storage::protocol_data_store::lmdb::LmdbProtocolDataStore;
use engine_storage::transaction_source::lmdb::LmdbEnvironment;
use engine_storage::trie_store::lmdb::LmdbTrieStore;
use engine_wasm_prep::wasm_costs::WasmCosts;
use protobuf::RepeatedField;
use transforms::TransformEntry;

use crate::test::{
    CONTRACT_MINT_INSTALL, CONTRACT_POS_INSTALL, CONTRACT_STANDARD_PAYMENT, DEFAULT_CHAIN_NAME,
    DEFAULT_GENESIS_TIMESTAMP, DEFAULT_PAYMENT, DEFAULT_PROTOCOL_VERSION, DEFAULT_WASM_COSTS,
};

pub const DEFAULT_BLOCK_TIME: u64 = 0;
pub const MOCKED_ACCOUNT_ADDRESS: [u8; 32] = [48u8; 32];
pub const COMPILED_WASM_PATH: &str = "../target/wasm32-unknown-unknown/release";
pub const GENESIS_INITIAL_BALANCE: u64 = 100_000_000_000;

/// LMDB initial map size is calculated based on DEFAULT_LMDB_PAGES and systems page size.
///
/// This default value should give 1MiB initial map size by default.
const DEFAULT_LMDB_PAGES: usize = 2560;

pub type InMemoryWasmTestBuilder = WasmTestBuilder<InMemoryGlobalState>;
pub type LmdbWasmTestBuilder = WasmTestBuilder<LmdbGlobalState>;

pub struct DeployItemBuilder {
    deploy_item: DeployItem,
}

impl DeployItemBuilder {
    pub fn new() -> Self {
        Default::default()
    }

    pub fn with_address(mut self, address: [u8; 32]) -> Self {
        self.deploy_item.set_address(address.to_vec());
        self
    }

    pub fn with_payment_code(mut self, file_name: &str, args: impl ArgsParser) -> Self {
        let wasm_bytes = read_wasm_file_bytes(file_name);
        let args = args
            .parse()
            .and_then(|args_bytes| ToBytes::to_bytes(&args_bytes))
            .expect("should serialize args");
        let mut deploy_code = DeployCode::new();
        deploy_code.set_args(args);
        deploy_code.set_code(wasm_bytes);
        let mut payment = DeployPayload::new();
        payment.set_deploy_code(deploy_code);
        self.deploy_item.set_payment(payment);
        self
    }

    pub fn with_stored_payment_hash(mut self, hash: Vec<u8>, args: impl ArgsParser) -> Self {
        let args = args
            .parse()
            .and_then(|args_bytes| ToBytes::to_bytes(&args_bytes))
            .expect("should serialize args");
        let mut item = StoredContractHash::new();
        item.set_args(args);
        item.set_hash(hash);
        let mut payment = DeployPayload::new();
        payment.set_stored_contract_hash(item);
        self.deploy_item.set_payment(payment);
        self
    }

    pub fn with_stored_payment_uref(mut self, uref: URef, args: impl ArgsParser) -> Self {
        let args = args
            .parse()
            .and_then(|args_bytes| ToBytes::to_bytes(&args_bytes))
            .expect("should serialize args");
        let mut item = StoredContractURef::new();
        item.set_args(args);
        item.set_uref(uref.addr().to_vec());
        let mut payment = DeployPayload::new();
        payment.set_stored_contract_uref(item);
        self.deploy_item.set_payment(payment);
        self
    }

    pub fn with_stored_payment_named_key(mut self, uref_name: &str, args: impl ArgsParser) -> Self {
        let args = args
            .parse()
            .and_then(|args_bytes| ToBytes::to_bytes(&args_bytes))
            .expect("should serialize args");
        let mut item = StoredContractName::new();
        item.set_args(args);
        item.set_stored_contract_name(uref_name.to_owned()); // <-- named uref
        let mut payment = DeployPayload::new();
        payment.set_stored_contract_name(item);
        self.deploy_item.set_payment(payment);
        self
    }

    pub fn with_session_code(mut self, file_name: &str, args: impl ArgsParser) -> Self {
        let wasm_bytes = read_wasm_file_bytes(file_name);
        let args = args
            .parse()
            .and_then(|args_bytes| ToBytes::to_bytes(&args_bytes))
            .expect("should serialize args");
        let mut deploy_code = DeployCode::new();
        deploy_code.set_code(wasm_bytes);
        deploy_code.set_args(args);
        let mut session = DeployPayload::new();
        session.set_deploy_code(deploy_code);
        self.deploy_item.set_session(session);
        self
    }

    pub fn with_stored_session_hash(mut self, hash: Vec<u8>, args: impl ArgsParser) -> Self {
        let args = args
            .parse()
            .and_then(|args_bytes| ToBytes::to_bytes(&args_bytes))
            .expect("should serialize args");
        let mut item: StoredContractHash = StoredContractHash::new();
        item.set_args(args);
        item.set_hash(hash);
        let mut session = DeployPayload::new();
        session.set_stored_contract_hash(item);
        self.deploy_item.set_session(session);
        self
    }

    pub fn with_stored_session_uref(mut self, uref: URef, args: impl ArgsParser) -> Self {
        let args = args
            .parse()
            .and_then(|args_bytes| ToBytes::to_bytes(&args_bytes))
            .expect("should serialize args");
        let mut item: StoredContractURef = StoredContractURef::new();
        item.set_args(args);
        item.set_uref(uref.addr().to_vec());
        let mut payment = DeployPayload::new();
        payment.set_stored_contract_uref(item);
        self.deploy_item.set_session(payment);
        self
    }

    pub fn with_stored_session_named_key(mut self, uref_name: &str, args: impl ArgsParser) -> Self {
        let args = args
            .parse()
            .and_then(|args_bytes| ToBytes::to_bytes(&args_bytes))
            .expect("should serialize args");
        let mut item = StoredContractName::new();
        item.set_args(args);
        item.set_stored_contract_name(uref_name.to_owned()); // <-- named uref
        let mut session = DeployPayload::new();
        session.set_stored_contract_name(item);
        self.deploy_item.set_session(session);
        self
    }

    pub fn with_authorization_keys(mut self, authorization_keys: &[PublicKey]) -> Self {
        let authorization_keys = authorization_keys
            .iter()
            .map(|public_key| public_key.value().to_vec())
            .collect();
        self.deploy_item.set_authorization_keys(authorization_keys);
        self
    }

    pub fn with_deploy_hash(mut self, hash: [u8; 32]) -> Self {
        self.deploy_item.set_deploy_hash(hash.to_vec());
        self
    }

    pub fn build(self) -> DeployItem {
        self.deploy_item
    }
}

impl Default for DeployItemBuilder {
    fn default() -> Self {
        let mut deploy_item = DeployItem::new();
        deploy_item.set_gas_price(1);
        DeployItemBuilder { deploy_item }
    }
}

pub struct ExecuteRequestBuilder {
    deploy_items: Vec<DeployItem>,
    execute_request: ExecuteRequest,
}

impl ExecuteRequestBuilder {
    pub fn new() -> Self {
        Default::default()
    }

    pub fn push_deploy(mut self, deploy: DeployItem) -> Self {
        self.deploy_items.push(deploy);
        self
    }

    pub fn with_pre_state_hash(mut self, pre_state_hash: &[u8]) -> Self {
        self.execute_request
            .set_parent_state_hash(pre_state_hash.to_vec());
        self
    }

    pub fn with_block_time(mut self, block_time: u64) -> Self {
        self.execute_request.set_block_time(block_time);
        self
    }

    pub fn with_protocol_version(mut self, version: u64) -> Self {
        let mut protocol_version = ProtocolVersion::new();
        protocol_version.set_value(version);
        self.execute_request.set_protocol_version(protocol_version);
        self
    }

    pub fn build(mut self) -> ExecuteRequest {
        let mut deploys = RepeatedField::<DeployItem>::new();
        for deploy in self.deploy_items {
            deploys.push(deploy);
        }
        self.execute_request.set_deploys(deploys);
        self.execute_request
    }

    pub fn standard(
        addr: [u8; 32],
        session_file: &str,
        session_args: impl ArgsParser,
    ) -> ExecuteRequest {
        let mut rng = rand::thread_rng();
        let deploy_hash: [u8; 32] = rng.gen();

        let deploy = DeployItemBuilder::new()
            .with_address(addr)
            .with_session_code(session_file, session_args)
            .with_payment_code(CONTRACT_STANDARD_PAYMENT, (*DEFAULT_PAYMENT,))
            .with_authorization_keys(&[PublicKey::new(addr)])
            .with_deploy_hash(deploy_hash)
            .build();

        ExecuteRequestBuilder::new().push_deploy(deploy).build()
    }
}

impl Default for ExecuteRequestBuilder {
    fn default() -> Self {
        let deploy_items = vec![];
        let mut execute_request = ExecuteRequest::new();
        execute_request.set_block_time(DEFAULT_BLOCK_TIME);
        let mut protocol_version = ProtocolVersion::new();
        protocol_version.set_value(1);
        execute_request.set_protocol_version(protocol_version);
        ExecuteRequestBuilder {
            deploy_items,
            execute_request,
        }
    }
}

pub struct UpgradeRequestBuilder {
    pre_state_hash: Vec<u8>,
    current_protocol_version: ProtocolVersion,
    new_protocol_version: ProtocolVersion,
    upgrade_installer: DeployCode,
    new_costs: Option<ChainSpec_CostTable_WasmCosts>,
    activation_point: ChainSpec_ActivationPoint,
}

impl UpgradeRequestBuilder {
    pub fn new() -> Self {
        Default::default()
    }

    pub fn with_pre_state_hash(mut self, pre_state_hash: &[u8]) -> Self {
        self.pre_state_hash = pre_state_hash.to_vec();
        self
    }

    pub fn with_current_protocol_version(mut self, protocol_version: u64) -> Self {
        self.current_protocol_version = {
            let mut ret = ProtocolVersion::new();
            ret.set_value(protocol_version);
            ret
        };
        self
    }

    pub fn with_new_protocol_version(mut self, protocol_version: u64) -> Self {
        self.new_protocol_version = {
            let mut ret = ProtocolVersion::new();
            ret.set_value(protocol_version);
            ret
        };
        self
    }

    pub fn with_installer_code(mut self, upgrade_installer: DeployCode) -> Self {
        self.upgrade_installer = upgrade_installer;
        self
    }

    pub fn with_new_costs(mut self, wasm_costs: WasmCosts) -> Self {
        let mut new_costs = ChainSpec_CostTable_WasmCosts::new();
        new_costs.set_regular(wasm_costs.regular);
        new_costs.set_opcodes_mul(wasm_costs.opcodes_mul);
        new_costs.set_opcodes_div(wasm_costs.opcodes_div);
        new_costs.set_mul(wasm_costs.mul);
        new_costs.set_div(wasm_costs.div);
        new_costs.set_grow_mem(wasm_costs.grow_mem);
        new_costs.set_initial_mem(wasm_costs.initial_mem);
        new_costs.set_max_stack_height(wasm_costs.max_stack_height);
        new_costs.set_mem(wasm_costs.mem);
        new_costs.set_memcpy(wasm_costs.memcpy);
        self.new_costs = Some(new_costs);
        self
    }

    pub fn with_activation_point(mut self, rank: u64) -> Self {
        self.activation_point = {
            let mut ret = ChainSpec_ActivationPoint::new();
            ret.set_rank(rank);
            ret
        };
        self
    }

    pub fn build(self) -> UpgradeRequest {
        let mut upgrade_point = ChainSpec_UpgradePoint::new();
        upgrade_point.set_activation_point(self.activation_point);
        match self.new_costs {
            None => {}
            Some(new_costs) => {
                let mut cost_table =
                    engine_grpc_server::engine_server::ipc::ChainSpec_CostTable::new();
                cost_table.set_wasm(new_costs);
                upgrade_point.set_new_costs(cost_table);
            }
        }
        upgrade_point.set_protocol_version(self.new_protocol_version);
        upgrade_point.set_upgrade_installer(self.upgrade_installer);

        let mut upgrade_request = UpgradeRequest::new();
        upgrade_request.set_protocol_version(self.current_protocol_version);
        upgrade_request.set_upgrade_point(upgrade_point);
        upgrade_request
    }
}

impl Default for UpgradeRequestBuilder {
    fn default() -> Self {
        UpgradeRequestBuilder {
            pre_state_hash: Default::default(),
            current_protocol_version: Default::default(),
            new_protocol_version: Default::default(),
            upgrade_installer: Default::default(),
            new_costs: None,
            activation_point: Default::default(),
        }
    }
}

pub fn get_protocol_version() -> ProtocolVersion {
    let mut protocol_version: ProtocolVersion = ProtocolVersion::new();
    protocol_version.set_value(1);
    protocol_version
}

pub fn get_mock_deploy() -> DeployItem {
    let mut deploy = DeployItem::new();
    deploy.set_address(MOCKED_ACCOUNT_ADDRESS.to_vec());
    deploy.set_gas_price(1);
    let deploy_payload = {
        let mut deploy_code = DeployCode::new();
        deploy_code.set_code(test_utils::create_empty_wasm_module_bytes());
        let mut deploy_payload = DeployPayload::new();
        deploy_payload.set_deploy_code(deploy_code);
        deploy_payload
    };
    deploy.set_session(deploy_payload);
    deploy.set_deploy_hash([1u8; 32].to_vec());
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
    MintInstall,
    ProofOfStake,
    ProofOfStakeInstall,
}

pub fn create_genesis_config(accounts: Vec<GenesisAccount>) -> GenesisConfig {
    let name = DEFAULT_CHAIN_NAME.to_string();
    let timestamp = DEFAULT_GENESIS_TIMESTAMP;
    let mint_installer_bytes = read_wasm_file_bytes(CONTRACT_MINT_INSTALL);
    let proof_of_stake_installer_bytes = read_wasm_file_bytes(CONTRACT_POS_INSTALL);
    let protocol_version = *DEFAULT_PROTOCOL_VERSION;
    let wasm_costs = *DEFAULT_WASM_COSTS;
    GenesisConfig::new(
        name,
        timestamp,
        protocol_version,
        mint_installer_bytes,
        proof_of_stake_installer_bytes,
        accounts,
        wasm_costs,
    )
}

pub fn create_query_request(post_state: Vec<u8>, base_key: Key, path: Vec<String>) -> QueryRequest {
    let mut query_request = QueryRequest::new();

    query_request.set_state_hash(post_state);
    query_request.set_base_key(base_key.into());
    query_request.set_path(path.into());

    query_request
}

#[allow(clippy::too_many_arguments)]
pub fn create_exec_request(
    address: [u8; 32],
    payment_file: &str,
    payment_args: impl ArgsParser,
    session_file: &str,
    session_args: impl ArgsParser,
    pre_state_hash: &[u8],
    block_time: u64,
    deploy_hash: [u8; 32],
    authorized_keys: Vec<PublicKey>,
) -> ExecuteRequest {
    let deploy = DeployItemBuilder::new()
        .with_session_code(session_file, session_args)
        .with_payment_code(payment_file, payment_args)
        .with_address(address)
        .with_authorization_keys(&authorized_keys)
        .with_deploy_hash(deploy_hash)
        .build();

    ExecuteRequestBuilder::new()
        .with_pre_state_hash(pre_state_hash)
        .with_protocol_version(1)
        .with_block_time(block_time)
        .push_deploy(deploy)
        .build()
}

#[allow(clippy::implicit_hasher)]
pub fn create_commit_request(
    prestate_hash: &[u8],
    effects: &HashMap<Key, Transform>,
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
pub fn get_genesis_transforms(genesis_response: &GenesisResponse) -> HashMap<Key, Transform> {
    let commit_transforms: CommitTransforms = genesis_response
        .get_success()
        .get_effect()
        .get_transform_map()
        .try_into()
        .expect("should convert");
    commit_transforms.value()
}

pub fn get_exec_transforms(exec_response: &ExecuteResponse) -> Vec<HashMap<Key, Transform>> {
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

pub fn get_exec_costs(exec_response: &ExecuteResponse) -> Vec<Gas> {
    let deploy_results: &[DeployResult] = exec_response.get_success().get_deploy_results();

    deploy_results
        .iter()
        .map(|deploy_result| Gas::from_u64(deploy_result.get_execution_result().get_cost()))
        .collect()
}

#[allow(clippy::implicit_hasher)]
pub fn get_contract_uref(transforms: &HashMap<Key, Transform>, contract: Vec<u8>) -> Option<URef> {
    transforms
        .iter()
        .find(|(_, v)| match v {
            Transform::Write(Value::Contract(mint_contract))
                if mint_contract.bytes() == contract.as_slice() =>
            {
                true
            }
            _ => false,
        })
        .and_then(|(k, _)| {
            if let Key::URef(uref) = k {
                Some(*uref)
            } else {
                None
            }
        })
}

#[allow(clippy::implicit_hasher)]
pub fn get_mint_contract_uref(
    transforms: &HashMap<Key, Transform>,
    contracts: &HashMap<SystemContractType, WasmiBytes>,
) -> Option<URef> {
    let mint_contract_bytes: Vec<u8> = contracts
        .get(&SystemContractType::Mint)
        .map(ToOwned::to_owned)
        .map(Into::into)
        .expect("Should get mint bytes.");

    get_contract_uref(&transforms, mint_contract_bytes)
}

#[allow(clippy::implicit_hasher)]
pub fn get_pos_contract_uref(
    transforms: &HashMap<Key, Transform>,
    contracts: &HashMap<SystemContractType, WasmiBytes>,
) -> Option<URef> {
    let mint_contract_bytes: Vec<u8> = contracts
        .get(&SystemContractType::ProofOfStake)
        .map(ToOwned::to_owned)
        .map(Into::into)
        .expect("Should get PoS bytes.");

    get_contract_uref(&transforms, mint_contract_bytes)
}

#[allow(clippy::implicit_hasher)]
pub fn get_account(transforms: &HashMap<Key, Transform>, account: &Key) -> Option<Account> {
    transforms.get(account).and_then(|transform| {
        if let Transform::Write(Value::Account(account)) = transform {
            Some(account.to_owned())
        } else {
            None
        }
    })
}

pub fn get_success_result(response: &ExecuteResponse) -> DeployResult_ExecutionResult {
    let result = response.get_success();

    result
        .get_deploy_results()
        .first()
        .expect("should have a deploy result")
        .get_execution_result()
        .to_owned()
}

pub fn get_precondition_failure(response: &ExecuteResponse) -> DeployResult_PreconditionFailure {
    let result = response.get_success();

    result
        .get_deploy_results()
        .first()
        .expect("should have a deploy result")
        .get_precondition_failure()
        .to_owned()
}

pub fn get_error_message(execution_result: DeployResult_ExecutionResult) -> String {
    let error = execution_result.get_error();

    if error.has_gas_error() {
        "Gas limit".to_string()
    } else {
        error.get_exec_error().get_message().to_string()
    }
}

pub const STANDARD_PAYMENT_CONTRACT: &str = "standard_payment.wasm";

/// Builder for simple WASM test
pub struct WasmTestBuilder<S> {
    /// Engine state is wrapped in Rc<> to workaround missing `impl Clone for
    /// EngineState`
    engine_state: Rc<EngineState<S>>,
    exec_responses: Vec<ExecuteResponse>,
    upgrade_responses: Vec<UpgradeResponse>,
    genesis_hash: Option<Vec<u8>>,
    post_state_hash: Option<Vec<u8>>,
    /// Cached transform maps after subsequent successful runs
    /// i.e. transforms[0] is for first run() call etc.
    transforms: Vec<HashMap<Key, Transform>>,
    bonded_validators: Vec<HashMap<PublicKey, U512>>,
    /// Cached genesis transforms
    genesis_account: Option<Account>,
    /// Genesis transforms
    genesis_transforms: Option<HashMap<Key, Transform>>,
    /// Mint contract uref
    mint_contract_uref: Option<URef>,
    /// PoS contract uref
    pos_contract_uref: Option<URef>,
}

impl Default for InMemoryWasmTestBuilder {
    fn default() -> Self {
        let engine_config = EngineConfig::new().set_use_payment_code(true);
        let global_state = InMemoryGlobalState::empty().expect("should create global state");
        let engine_state = EngineState::new(global_state, engine_config);

        WasmTestBuilder {
            engine_state: Rc::new(engine_state),
            exec_responses: Vec::new(),
            upgrade_responses: Vec::new(),
            genesis_hash: None,
            post_state_hash: None,
            transforms: Vec::new(),
            bonded_validators: Vec::new(),
            genesis_account: None,
            mint_contract_uref: None,
            pos_contract_uref: None,
            genesis_transforms: None,
        }
    }
}

// TODO: Deriving `Clone` for `WasmTestBuilder<S>` doesn't work correctly (unsure why), so
// implemented by hand here.  Try to derive in the future with a different compiler version.
impl<S> Clone for WasmTestBuilder<S> {
    fn clone(&self) -> Self {
        WasmTestBuilder {
            engine_state: Rc::clone(&self.engine_state),
            exec_responses: self.exec_responses.clone(),
            upgrade_responses: self.upgrade_responses.clone(),
            genesis_hash: self.genesis_hash.clone(),
            post_state_hash: self.post_state_hash.clone(),
            transforms: self.transforms.clone(),
            bonded_validators: self.bonded_validators.clone(),
            genesis_account: self.genesis_account.clone(),
            mint_contract_uref: self.mint_contract_uref,
            pos_contract_uref: self.pos_contract_uref,
            genesis_transforms: self.genesis_transforms.clone(),
        }
    }
}

/// A wrapper type to disambiguate builder from an actual result
#[derive(Clone)]
pub struct WasmTestResult<S>(WasmTestBuilder<S>);

impl<S> WasmTestResult<S> {
    /// Access the builder
    pub fn builder(&self) -> &WasmTestBuilder<S> {
        &self.0
    }
}

impl InMemoryWasmTestBuilder {
    pub fn new(engine_config: EngineConfig) -> Self {
        let global_state = InMemoryGlobalState::empty().expect("should create global state");
        let engine_state = EngineState::new(global_state, engine_config);
        WasmTestBuilder {
            engine_state: Rc::new(engine_state),
            ..Default::default()
        }
    }
}

impl LmdbWasmTestBuilder {
    pub fn new_with_config<T: AsRef<OsStr> + ?Sized>(
        data_dir: &T,
        engine_config: EngineConfig,
    ) -> Self {
        let page_size = get_page_size().expect("should get page size");
        let environment = Arc::new(
            LmdbEnvironment::new(&data_dir.into(), page_size * DEFAULT_LMDB_PAGES)
                .expect("should create LmdbEnvironment"),
        );
        let trie_store = Arc::new(
            LmdbTrieStore::new(&environment, None, DatabaseFlags::empty())
                .expect("should create LmdbTrieStore"),
        );
        let protocol_data_store = Arc::new(
            LmdbProtocolDataStore::new(&environment, None, DatabaseFlags::empty())
                .expect("should create LmdbProtocolDataStore"),
        );
        let global_state = LmdbGlobalState::empty(environment, trie_store, protocol_data_store)
            .expect("should create LmdbGlobalState");
        let engine_state = EngineState::new(global_state, engine_config);
        WasmTestBuilder {
            engine_state: Rc::new(engine_state),
            exec_responses: Vec::new(),
            upgrade_responses: Vec::new(),
            genesis_hash: None,
            post_state_hash: None,
            transforms: Vec::new(),
            bonded_validators: Vec::new(),
            genesis_account: None,
            mint_contract_uref: None,
            pos_contract_uref: None,
            genesis_transforms: None,
        }
    }

    pub fn new<T: AsRef<OsStr> + ?Sized>(data_dir: &T) -> Self {
        Self::new_with_config(data_dir, Default::default())
    }

    /// Creates new instance of builder and applies values only which allows the engine state to be
    /// swapped with a new one, possibly after running genesis once and reusing existing database
    /// (i.e. LMDB).
    pub fn new_with_config_and_result<T: AsRef<OsStr> + ?Sized>(
        data_dir: &T,
        engine_config: EngineConfig,
        result: &WasmTestResult<LmdbGlobalState>,
    ) -> Self {
        let mut builder = Self::new_with_config(data_dir, engine_config);
        // Applies existing properties from gi
        builder.genesis_hash = result.0.genesis_hash.clone();
        builder.post_state_hash = result.0.post_state_hash.clone();
        builder.bonded_validators = result.0.bonded_validators.clone();
        builder.mint_contract_uref = result.0.mint_contract_uref;
        builder.pos_contract_uref = result.0.pos_contract_uref;
        builder
    }

    /// Creates a new instance of builder using the supplied configurations, opening wrapped LMDBs
    /// (e.g. in the Trie and Data stores) rather than creating them.
    pub fn open<T: AsRef<OsStr> + ?Sized>(
        data_dir: &T,
        engine_config: EngineConfig,
        post_state_hash: Vec<u8>,
    ) -> Self {
        let page_size = get_page_size().expect("should get page size");
        let environment = Arc::new(
            LmdbEnvironment::new(&data_dir.into(), page_size * DEFAULT_LMDB_PAGES)
                .expect("should create LmdbEnvironment"),
        );
        let trie_store =
            Arc::new(LmdbTrieStore::open(&environment, None).expect("should open LmdbTrieStore"));
        let protocol_data_store = Arc::new(
            LmdbProtocolDataStore::open(&environment, None)
                .expect("should open LmdbProtocolDataStore"),
        );
        let global_state = LmdbGlobalState::empty(environment, trie_store, protocol_data_store)
            .expect("should create LmdbGlobalState");
        let engine_state = EngineState::new(global_state, engine_config);
        WasmTestBuilder {
            engine_state: Rc::new(engine_state),
            exec_responses: Vec::new(),
            upgrade_responses: Vec::new(),
            genesis_hash: None,
            post_state_hash: Some(post_state_hash),
            transforms: Vec::new(),
            bonded_validators: Vec::new(),
            genesis_account: None,
            mint_contract_uref: None,
            pos_contract_uref: None,
            genesis_transforms: None,
        }
    }
}

impl<S> WasmTestBuilder<S>
where
    S: StateProvider,
    S::Error: Into<execution::Error>,
    EngineState<S>: ExecutionEngineService,
{
    /// Carries on attributes from TestResult for further executions
    pub fn from_result(result: WasmTestResult<S>) -> Self {
        WasmTestBuilder {
            engine_state: result.0.engine_state,
            exec_responses: Vec::new(),
            upgrade_responses: Vec::new(),
            genesis_hash: result.0.genesis_hash,
            post_state_hash: result.0.post_state_hash,
            transforms: Vec::new(),
            bonded_validators: result.0.bonded_validators,
            genesis_account: result.0.genesis_account,
            mint_contract_uref: result.0.mint_contract_uref,
            pos_contract_uref: result.0.pos_contract_uref,
            genesis_transforms: result.0.genesis_transforms,
        }
    }

    pub fn run_genesis(&mut self, genesis_config: &GenesisConfig) -> &mut Self {
        let system_account = Key::Account(SYSTEM_ACCOUNT_ADDR);
        let genesis_config = genesis_config
            .to_owned()
            .try_into()
            .expect("could not parse");

        let genesis_response = self
            .engine_state
            .run_genesis_with_chainspec(RequestOptions::new(), genesis_config)
            .wait_drop_metadata()
            .expect("Unable to get genesis response");

        if genesis_response.has_failed_deploy() {
            panic!(
                "genesis failure: {:?}",
                genesis_response.get_failed_deploy().to_owned()
            );
        }

        let state_root_hash: Blake2bHash = genesis_response
            .get_success()
            .get_poststate_hash()
            .try_into()
            .expect("Unable to get root hash");

        let transforms = get_genesis_transforms(&genesis_response);

        let genesis_account =
            get_account(&transforms, &system_account).expect("Unable to get system account");

        let named_keys = genesis_account.named_keys();

        let mint_contract_uref = named_keys
            .get(MINT_NAME)
            .and_then(Key::as_uref)
            .cloned()
            .expect("Unable to get mint contract URef");

        let pos_contract_uref = named_keys
            .get(POS_NAME)
            .and_then(Key::as_uref)
            .cloned()
            .expect("Unable to get pos contract URef");

        self.genesis_hash = Some(state_root_hash.to_vec());
        self.post_state_hash = Some(state_root_hash.to_vec());
        self.mint_contract_uref = Some(mint_contract_uref);
        self.pos_contract_uref = Some(pos_contract_uref);
        self.genesis_account = Some(genesis_account);
        self.genesis_transforms = Some(transforms);
        self
    }

    pub fn query(
        &self,
        maybe_post_state: Option<Vec<u8>>,
        base_key: Key,
        path: &[&str],
    ) -> Option<Value> {
        let post_state = maybe_post_state
            .or_else(|| self.post_state_hash.clone())
            .expect("builder must have a post-state hash");

        let path_vec: Vec<String> = path.iter().map(|s| String::from(*s)).collect();

        let query_request = create_query_request(post_state, base_key, path_vec);

        let query_response = self
            .engine_state
            .query(RequestOptions::new(), query_request)
            .wait_drop_metadata()
            .expect("should query");

        if query_response.has_success() {
            query_response.get_success().try_into().ok()
        } else {
            None
        }
    }

    pub fn exec_with_exec_request(&mut self, mut exec_request: ExecuteRequest) -> &mut Self {
        let exec_request = {
            let hash = self
                .post_state_hash
                .clone()
                .expect("expected post_state_hash");
            exec_request.set_parent_state_hash(hash.to_vec());
            exec_request
        };
        let exec_response = self
            .engine_state
            .execute(RequestOptions::new(), exec_request)
            .wait_drop_metadata()
            .expect("should exec");
        self.exec_responses.push(exec_response.clone());
        assert!(exec_response.has_success());
        // Parse deploy results
        let deploy_result = exec_response
            .get_success()
            .get_deploy_results()
            .get(0) // We only allow for issuing single deploy (one wasm file).
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

    /// Runs a contract and after that runs actual WASM contract and expects
    /// transformations to happen at the end of execution.
    #[allow(clippy::too_many_arguments)]
    pub fn exec_with_args_and_keys(
        &mut self,
        address: [u8; 32],
        payment_file: &str,
        payment_args: impl ArgsParser,
        session_file: &str,
        session_args: impl ArgsParser,
        block_time: u64,
        deploy_hash: [u8; 32],
        authorized_keys: Vec<PublicKey>,
    ) -> &mut Self {
        let exec_request = create_exec_request(
            address,
            payment_file,
            payment_args,
            session_file,
            session_args,
            self.post_state_hash
                .as_ref()
                .expect("Should have post state hash"),
            block_time,
            deploy_hash,
            authorized_keys,
        );
        self.exec_with_exec_request(exec_request)
    }

    #[allow(clippy::too_many_arguments)]
    pub fn exec_with_args(
        &mut self,
        address: [u8; 32],
        payment_file: &str,
        payment_args: impl ArgsParser,
        session_file: &str,
        session_args: impl ArgsParser,
        block_time: u64,
        deploy_hash: [u8; 32],
    ) -> &mut Self {
        self.exec_with_args_and_keys(
            address,
            payment_file,
            payment_args,
            session_file,
            session_args,
            block_time,
            deploy_hash,
            // Exec with different account also implies the authorized keys should default to
            // the calling account.
            vec![PublicKey::new(address)],
        )
    }

    pub fn exec(
        &mut self,
        address: [u8; 32],
        session_file: &str,
        block_time: u64,
        deploy_hash: [u8; 32],
    ) -> &mut Self {
        let payment_file = STANDARD_PAYMENT_CONTRACT;
        let payment_args = (U512::from(MAX_PAYMENT),);
        self.exec_with_args(
            address,
            payment_file,
            payment_args,
            session_file,
            (), // no arguments passed to session contract by default
            block_time,
            deploy_hash,
        )
    }

    /// Commit effects of previous exec call on the latest post-state hash.
    pub fn commit(&mut self) -> &mut Self {
        let prestate_hash = self
            .post_state_hash
            .clone()
            .expect("Should have genesis hash");

        let effects = self
            .transforms
            .last()
            .cloned()
            .expect("Should have transforms to commit.");

        self.commit_effects(prestate_hash, effects)
    }

    /// Runs a commit request, expects a successful response, and
    /// overwrites existing cached post state hash with a new one.
    pub fn commit_effects(
        &mut self,
        prestate_hash: Vec<u8>,
        effects: HashMap<Key, Transform>,
    ) -> &mut Self {
        let commit_request = create_commit_request(&prestate_hash, &effects);

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
        let commit_success = commit_response.get_success();
        self.post_state_hash = Some(commit_success.get_poststate_hash().to_vec());
        let bonded_validators = commit_success
            .get_bonded_validators()
            .iter()
            .map(TryInto::try_into)
            .collect::<Result<HashMap<PublicKey, U512>, MappingError>>()
            .unwrap();
        self.bonded_validators.push(bonded_validators);
        self
    }

    pub fn upgrade_with_upgrade_request(
        &mut self,
        upgrade_request: &mut UpgradeRequest,
    ) -> &mut Self {
        let upgrade_request = {
            let hash = self
                .post_state_hash
                .clone()
                .expect("expected post_state_hash");
            upgrade_request.set_parent_state_hash(hash.to_vec());
            upgrade_request
        };
        let upgrade_response = self
            .engine_state
            .upgrade(RequestOptions::new(), upgrade_request.clone())
            .wait_drop_metadata()
            .expect("should upgrade");

        let upgrade_success = upgrade_response.get_success();
        self.post_state_hash = Some(upgrade_success.get_post_state_hash().to_vec());

        self.upgrade_responses.push(upgrade_response.clone());
        self
    }

    /// Expects a successful run and caches transformations
    pub fn expect_success(&mut self) -> &mut Self {
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
        if !deploy_result.has_execution_result() {
            panic!("Expected ExecutionResult, got {:?} instead", deploy_result);
        }

        if deploy_result.get_execution_result().has_error() {
            panic!(
                "Expected successful execution result, but instead got: {:?}",
                exec_response,
            );
        }
        self
    }

    pub fn is_error(&self) -> bool {
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
        deploy_result.get_execution_result().has_error()
    }

    /// Gets the transform map that's cached between runs
    pub fn get_transforms(&self) -> Vec<HashMap<Key, Transform>> {
        self.transforms.clone()
    }

    pub fn get_bonded_validators(&self) -> Vec<HashMap<PublicKey, U512>> {
        self.bonded_validators.clone()
    }

    /// Gets genesis account (if present)
    pub fn get_genesis_account(&self) -> &Account {
        self.genesis_account
            .as_ref()
            .expect("Unable to obtain genesis account. Please run genesis first.")
    }

    pub fn get_mint_contract_uref(&self) -> URef {
        self.mint_contract_uref
            .expect("Unable to obtain mint contract uref. Please run genesis first.")
    }

    pub fn get_pos_contract_uref(&self) -> URef {
        self.pos_contract_uref
            .expect("Unable to obtain pos contract uref. Please run genesis first.")
    }

    pub fn get_genesis_transforms(&self) -> &HashMap<Key, engine_shared::transform::Transform> {
        &self
            .genesis_transforms
            .as_ref()
            .expect("should have genesis transforms")
    }

    pub fn get_genesis_hash(&self) -> Vec<u8> {
        self.genesis_hash
            .clone()
            .expect("Genesis hash should be present. Should be called after run_genesis.")
    }

    pub fn get_post_state_hash(&self) -> Vec<u8> {
        self.post_state_hash
            .clone()
            .expect("Should have post-state hash.")
    }

    pub fn get_engine_state(&self) -> &EngineState<S> {
        &self.engine_state
    }

    pub fn get_exec_response(&self, index: usize) -> Option<&ExecuteResponse> {
        self.exec_responses.get(index)
    }

    pub fn get_upgrade_response(&self, index: usize) -> Option<&UpgradeResponse> {
        self.upgrade_responses.get(index)
    }

    pub fn finish(&self) -> WasmTestResult<S> {
        WasmTestResult(self.clone())
    }

    pub fn get_pos_contract(&self) -> Contract {
        let system_account = Key::Account(SYSTEM_ACCOUNT_ADDR);
        self.query(None, system_account, &[POS_NAME])
            .and_then(|v| v.try_into().ok())
            .expect("should find PoS URef")
    }

    pub fn get_purse_balance(&self, purse_id: PurseId) -> U512 {
        let mint = self.get_mint_contract_uref();
        let purse_addr = purse_id.value().addr();
        let purse_bytes =
            ToBytes::to_bytes(&purse_addr).expect("should be able to serialize purse bytes");
        let balance_mapping_key = Key::local(mint.addr(), &purse_bytes);
        let balance_uref = self
            .query(None, balance_mapping_key, &[])
            .and_then(|v| v.try_into().ok())
            .expect("should find balance uref");

        self.query(None, balance_uref, &[])
            .and_then(|v| v.try_into().ok())
            .expect("should parse balance into a U512")
    }

    pub fn get_account(&self, addr: [u8; 32]) -> Option<Account> {
        let account_value = self
            .query(None, Key::Account(addr), &[])
            .expect("should query account");

        if let Value::Account(account) = account_value {
            Some(account)
        } else {
            None
        }
    }

    pub fn get_contract(&self, contract_uref: URef) -> Option<Contract> {
        let contract_value: Value = self
            .query(None, Key::URef(contract_uref), &[])
            .expect("should have contract value");

        if let Value::Contract(contract) = contract_value {
            Some(contract)
        } else {
            None
        }
    }

    pub fn exec_commit_finish(&mut self, execute_request: ExecuteRequest) -> WasmTestResult<S> {
        self.exec_with_exec_request(execute_request)
            .expect_success()
            .commit()
            .finish()
    }
}

/// Represents the difference between two [`HashMap`]s.
#[derive(Debug, Default, PartialEq, Eq)]
pub struct Diff {
    left: HashMap<Key, Transform>,
    both: HashMap<Key, Transform>,
    right: HashMap<Key, Transform>,
}

impl Diff {
    /// Creates a diff from two [`HashMap`]s.
    pub fn new(left: HashMap<Key, Transform>, right: HashMap<Key, Transform>) -> Diff {
        let both = Default::default();
        let left_clone = left.clone();
        let mut ret = Diff { left, both, right };

        for key in left_clone.keys() {
            let l = ret.left.remove_entry(key);
            let r = ret.right.remove_entry(key);

            match (l, r) {
                (Some(le), Some(re)) => {
                    if le == re {
                        ret.both.insert(*key, re.1);
                    } else {
                        ret.left.insert(*key, le.1);
                        ret.right.insert(*key, re.1);
                    }
                }
                (None, Some(re)) => {
                    ret.right.insert(*key, re.1);
                }
                (Some(le), None) => {
                    ret.left.insert(*key, le.1);
                }
                (None, None) => unreachable!(),
            }
        }

        ret
    }

    /// Returns the entries that are unique to the `left` input.
    pub fn left(&self) -> &HashMap<Key, Transform> {
        &self.left
    }

    /// Returns the entries that are unique to the `right` input.
    pub fn right(&self) -> &HashMap<Key, Transform> {
        &self.right
    }

    /// Returns the entries shared by both inputs.
    pub fn both(&self) -> &HashMap<Key, Transform> {
        &self.both
    }
}
