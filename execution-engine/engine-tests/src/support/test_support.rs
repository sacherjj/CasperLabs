use std::{
    collections::HashMap,
    convert::{TryFrom, TryInto},
    ffi::OsStr,
    fs,
    path::{Path, PathBuf},
    rc::Rc,
    sync::Arc,
};

use grpc::RequestOptions;
use lazy_static::lazy_static;
use lmdb::DatabaseFlags;
use protobuf::RepeatedField;
use rand::Rng;

use contract_ffi::{
    args_parser::ArgsParser,
    bytesrepr::ToBytes,
    key::Key,
    uref::URef,
    value::{
        account::{PublicKey, PurseId},
        CLValue, SemVer, U512,
    },
};
use engine_core::{
    engine_state::{
        genesis::{GenesisAccount, GenesisConfig},
        EngineConfig, EngineState, SYSTEM_ACCOUNT_ADDR,
    },
    execution,
};
use engine_grpc_server::engine_server::{
    ipc::{
        ChainSpec_ActivationPoint, ChainSpec_CostTable_WasmCosts, ChainSpec_UpgradePoint,
        CommitRequest, CommitResponse, DeployCode, DeployItem, DeployPayload, DeployResult,
        DeployResult_ExecutionResult, DeployResult_PreconditionFailure, ExecuteRequest,
        ExecuteResponse, GenesisResponse, QueryRequest, StoredContractHash, StoredContractName,
        StoredContractURef, UpgradeRequest, UpgradeResponse,
    },
    ipc_grpc::ExecutionEngineService,
    mappings::{MappingError, TransformMap},
    state::{self, ProtocolVersion},
    transforms::TransformEntry,
};
use engine_shared::{
    account::Account, additive_map::AdditiveMap, contract::Contract, gas::Gas,
    newtypes::Blake2bHash, os::get_page_size, stored_value::StoredValue, transform::Transform,
};
use engine_storage::{
    global_state::{in_memory::InMemoryGlobalState, lmdb::LmdbGlobalState, StateProvider},
    protocol_data_store::lmdb::LmdbProtocolDataStore,
    transaction_source::lmdb::LmdbEnvironment,
    trie_store::lmdb::LmdbTrieStore,
};
use engine_wasm_prep::wasm_costs::WasmCosts;

use crate::test::{
    CONTRACT_MINT_INSTALL, CONTRACT_POS_INSTALL, CONTRACT_STANDARD_PAYMENT, DEFAULT_CHAIN_NAME,
    DEFAULT_GENESIS_TIMESTAMP, DEFAULT_PAYMENT, DEFAULT_PROTOCOL_VERSION, DEFAULT_WASM_COSTS,
};

pub const STANDARD_PAYMENT_CONTRACT: &str = "standard_payment.wasm";
pub const COMPILED_WASM_DEFAULT_PATH: &str = "../target/wasm32-unknown-unknown/release";
pub const COMPILED_WASM_TYPESCRIPT_PATH: &str = "../target-as";
pub const DEFAULT_BLOCK_TIME: u64 = 0;
pub const MOCKED_ACCOUNT_ADDRESS: [u8; 32] = [48u8; 32];
pub const GENESIS_INITIAL_BALANCE: u64 = 100_000_000_000;

/// LMDB initial map size is calculated based on DEFAULT_LMDB_PAGES and systems page size.
///
/// This default value should give 1MiB initial map size by default.
const DEFAULT_LMDB_PAGES: usize = 2560;

/// This is appended to the data dir path provided to the `LmdbWasmTestBuilder` in order to match
/// the behavior of `get_data_dir()` in "engine-grpc-server/src/main.rs".
const GLOBAL_STATE_DIR: &str = "global_state";

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
        let args = Self::serialize_args(args);
        let mut deploy_code = DeployCode::new();
        deploy_code.set_args(args);
        deploy_code.set_code(wasm_bytes);
        let mut payment = DeployPayload::new();
        payment.set_deploy_code(deploy_code);
        self.deploy_item.set_payment(payment);
        self
    }

    pub fn with_stored_payment_hash(mut self, hash: Vec<u8>, args: impl ArgsParser) -> Self {
        let args = Self::serialize_args(args);
        let mut item = StoredContractHash::new();
        item.set_args(args);
        item.set_hash(hash);
        let mut payment = DeployPayload::new();
        payment.set_stored_contract_hash(item);
        self.deploy_item.set_payment(payment);
        self
    }

    pub fn with_stored_payment_uref(mut self, uref: URef, args: impl ArgsParser) -> Self {
        let args = Self::serialize_args(args);
        let mut item = StoredContractURef::new();
        item.set_args(args);
        item.set_uref(uref.addr().to_vec());
        let mut payment = DeployPayload::new();
        payment.set_stored_contract_uref(item);
        self.deploy_item.set_payment(payment);
        self
    }

    pub fn with_stored_payment_named_key(mut self, uref_name: &str, args: impl ArgsParser) -> Self {
        let args = Self::serialize_args(args);
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
        let args = Self::serialize_args(args);
        let mut deploy_code = DeployCode::new();
        deploy_code.set_code(wasm_bytes);
        deploy_code.set_args(args);
        let mut session = DeployPayload::new();
        session.set_deploy_code(deploy_code);
        self.deploy_item.set_session(session);
        self
    }

    pub fn with_stored_session_hash(mut self, hash: Vec<u8>, args: impl ArgsParser) -> Self {
        let args = Self::serialize_args(args);
        let mut item: StoredContractHash = StoredContractHash::new();
        item.set_args(args);
        item.set_hash(hash);
        let mut session = DeployPayload::new();
        session.set_stored_contract_hash(item);
        self.deploy_item.set_session(session);
        self
    }

    pub fn with_stored_session_uref(mut self, uref: URef, args: impl ArgsParser) -> Self {
        let args = Self::serialize_args(args);
        let mut item: StoredContractURef = StoredContractURef::new();
        item.set_args(args);
        item.set_uref(uref.addr().to_vec());
        let mut payment = DeployPayload::new();
        payment.set_stored_contract_uref(item);
        self.deploy_item.set_session(payment);
        self
    }

    pub fn with_stored_session_named_key(mut self, uref_name: &str, args: impl ArgsParser) -> Self {
        let args = Self::serialize_args(args);
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

    fn serialize_args(args: impl ArgsParser) -> Vec<u8> {
        args.parse_to_vec_u8()
            .expect("should convert to `Vec<CLValue>`")
            .into_bytes()
            .expect("should serialize args")
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

    pub fn from_deploy_item(deploy_item: DeployItem) -> Self {
        ExecuteRequestBuilder::new().push_deploy(deploy_item)
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

    pub fn with_protocol_version(
        mut self,
        protocol_version: contract_ffi::value::ProtocolVersion,
    ) -> Self {
        let mut protocol = state::ProtocolVersion::new();
        protocol.set_major(protocol_version.value().major);
        protocol.set_minor(protocol_version.value().minor);
        protocol.set_patch(protocol_version.value().patch);
        self.execute_request.set_protocol_version(protocol);
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

    pub fn standard(addr: [u8; 32], session_file: &str, session_args: impl ArgsParser) -> Self {
        let mut rng = rand::thread_rng();
        let deploy_hash: [u8; 32] = rng.gen();

        let deploy = DeployItemBuilder::new()
            .with_address(addr)
            .with_session_code(session_file, session_args)
            .with_payment_code(CONTRACT_STANDARD_PAYMENT, (*DEFAULT_PAYMENT,))
            .with_authorization_keys(&[PublicKey::new(addr)])
            .with_deploy_hash(deploy_hash)
            .build();

        ExecuteRequestBuilder::new().push_deploy(deploy)
    }

    pub fn contract_call_by_hash(
        sender: [u8; 32],
        contract_hash: [u8; 32],
        args: impl ArgsParser,
    ) -> Self {
        let mut rng = rand::thread_rng();
        let deploy_hash: [u8; 32] = rng.gen();

        let deploy = DeployItemBuilder::new()
            .with_address(sender)
            .with_stored_session_hash(contract_hash.to_vec(), args)
            .with_payment_code(CONTRACT_STANDARD_PAYMENT, (*DEFAULT_PAYMENT,))
            .with_authorization_keys(&[PublicKey::new(sender)])
            .with_deploy_hash(deploy_hash)
            .build();

        ExecuteRequestBuilder::new().push_deploy(deploy)
    }
}

impl Default for ExecuteRequestBuilder {
    fn default() -> Self {
        let deploy_items = vec![];
        let mut execute_request = ExecuteRequest::new();
        execute_request.set_block_time(DEFAULT_BLOCK_TIME);
        execute_request.set_protocol_version(get_protocol_version());
        ExecuteRequestBuilder {
            deploy_items,
            execute_request,
        }
    }
}

pub fn get_protocol_version() -> ProtocolVersion {
    let mut protocol_version: ProtocolVersion = ProtocolVersion::new();
    let sem_ver = SemVer::V1_0_0;
    protocol_version.set_major(sem_ver.major);
    protocol_version.set_minor(sem_ver.minor);
    protocol_version.set_patch(sem_ver.patch);
    protocol_version
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

    pub fn with_current_protocol_version(
        mut self,
        protocol_version: contract_ffi::value::ProtocolVersion,
    ) -> Self {
        self.current_protocol_version = {
            let mut protocol = ProtocolVersion::new();
            protocol.set_major(protocol_version.value().major);
            protocol.set_minor(protocol_version.value().minor);
            protocol.set_patch(protocol_version.value().patch);
            protocol
        };
        self
    }

    pub fn with_new_protocol_version(
        mut self,
        protocol_version: contract_ffi::value::ProtocolVersion,
    ) -> Self {
        self.new_protocol_version = {
            let mut protocol = ProtocolVersion::new();
            protocol.set_major(protocol_version.value().major);
            protocol.set_minor(protocol_version.value().minor);
            protocol.set_patch(protocol_version.value().patch);
            protocol
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

/// Builder for simple WASM test
pub struct WasmTestBuilder<S> {
    /// [`EngineState`] is wrapped in [`Rc`] to work around a missing [`Clone`] implementation
    engine_state: Rc<EngineState<S>>,
    exec_responses: Vec<ExecuteResponse>,
    upgrade_responses: Vec<UpgradeResponse>,
    genesis_hash: Option<Vec<u8>>,
    post_state_hash: Option<Vec<u8>>,
    /// Cached transform maps after subsequent successful runs i.e. `transforms[0]` is for first
    /// exec call etc.
    transforms: Vec<AdditiveMap<Key, Transform>>,
    bonded_validators: Vec<HashMap<PublicKey, U512>>,
    /// Cached genesis transforms
    genesis_account: Option<Account>,
    /// Genesis transforms
    genesis_transforms: Option<AdditiveMap<Key, Transform>>,
    /// Mint contract uref
    mint_contract_uref: Option<URef>,
    /// PoS contract uref
    pos_contract_uref: Option<URef>,
}

impl Default for InMemoryWasmTestBuilder {
    fn default() -> Self {
        let engine_config = EngineConfig::new();
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
    pub fn new(
        global_state: InMemoryGlobalState,
        engine_config: EngineConfig,
        post_state_hash: Vec<u8>,
    ) -> Self {
        let engine_state = EngineState::new(global_state, engine_config);
        WasmTestBuilder {
            engine_state: Rc::new(engine_state),
            genesis_hash: Some(post_state_hash.clone()),
            post_state_hash: Some(post_state_hash),
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
        let global_state_dir = Self::create_and_get_global_state_dir(data_dir);
        let environment = Arc::new(
            LmdbEnvironment::new(&global_state_dir, page_size * DEFAULT_LMDB_PAGES)
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
        let global_state_dir = Self::create_and_get_global_state_dir(data_dir);
        let environment = Arc::new(
            LmdbEnvironment::new(&global_state_dir, page_size * DEFAULT_LMDB_PAGES)
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

    fn create_and_get_global_state_dir<T: AsRef<OsStr> + ?Sized>(data_dir: &T) -> PathBuf {
        let global_state_path = {
            let mut path = PathBuf::from(data_dir);
            path.push(GLOBAL_STATE_DIR);
            path
        };
        fs::create_dir_all(&global_state_path)
            .unwrap_or_else(|_| panic!("Expected to create {}", global_state_path.display()));
        global_state_path
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
        let genesis_config_proto = genesis_config
            .to_owned()
            .try_into()
            .expect("could not parse");

        let genesis_response = self
            .engine_state
            .run_genesis(RequestOptions::new(), genesis_config_proto)
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

        let maybe_protocol_data = self
            .engine_state
            .get_protocol_data(genesis_config.protocol_version())
            .expect("should read protocol data");
        let protocol_data = maybe_protocol_data.expect("should have protocol data stored");

        self.genesis_hash = Some(state_root_hash.to_vec());
        self.post_state_hash = Some(state_root_hash.to_vec());
        self.mint_contract_uref = Some(protocol_data.mint());
        self.pos_contract_uref = Some(protocol_data.proof_of_stake());
        self.genesis_account = Some(genesis_account);
        self.genesis_transforms = Some(transforms);
        self
    }

    pub fn query(
        &self,
        maybe_post_state: Option<Vec<u8>>,
        base_key: Key,
        path: &[&str],
    ) -> Option<StoredValue> {
        let post_state = maybe_post_state
            .or_else(|| self.post_state_hash.clone())
            .expect("builder must have a post-state hash");

        let path_vec: Vec<String> = path.iter().map(|s| String::from(*s)).collect();

        let query_request = create_query_request(post_state, base_key, path_vec);

        let mut query_response = self
            .engine_state
            .query(RequestOptions::new(), query_request)
            .wait_drop_metadata()
            .expect("should get query response");

        if query_response.has_success() {
            query_response.take_success().try_into().ok()
        } else {
            None
        }
    }

    pub fn exec(&mut self, mut exec_request: ExecuteRequest) -> &mut Self {
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
        let commit_transforms: TransformMap = deploy_result
            .get_execution_result()
            .get_effects()
            .get_transform_map()
            .to_vec()
            .try_into()
            .expect("should convert");
        let transforms = commit_transforms.into_inner();
        // Cache transformations
        self.transforms.push(transforms);
        self
    }

    /// Commit effects of previous exec call on the latest post-state hash.
    pub fn commit(&mut self) -> &mut Self {
        let prestate_hash = self
            .post_state_hash
            .clone()
            .expect("Should have genesis hash");

        let effects = self.transforms.last().cloned().unwrap_or_default();

        self.commit_effects(prestate_hash, effects)
    }

    /// Sends raw commit request to the current engine response.
    ///
    /// Can be used where result is not necesary
    pub fn commit_transforms(
        &self,
        prestate_hash: Vec<u8>,
        effects: AdditiveMap<Key, Transform>,
    ) -> CommitResponse {
        let commit_request = create_commit_request(&prestate_hash, &effects);

        self.engine_state
            .commit(RequestOptions::new(), commit_request)
            .wait_drop_metadata()
            .expect("Should have commit response")
    }

    /// Runs a commit request, expects a successful response, and
    /// overwrites existing cached post state hash with a new one.
    pub fn commit_effects(
        &mut self,
        prestate_hash: Vec<u8>,
        effects: AdditiveMap<Key, Transform>,
    ) -> &mut Self {
        let mut commit_response = self.commit_transforms(prestate_hash, effects);
        if !commit_response.has_success() {
            panic!(
                "Expected commit success but received a failure instead: {:?}",
                commit_response
            );
        }
        let mut commit_success = commit_response.take_success();
        self.post_state_hash = Some(commit_success.take_poststate_hash().to_vec());
        let bonded_validators = commit_success
            .take_bonded_validators()
            .into_iter()
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
    pub fn get_transforms(&self) -> Vec<AdditiveMap<Key, Transform>> {
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

    pub fn get_genesis_transforms(&self) -> &AdditiveMap<Key, engine_shared::transform::Transform> {
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
        let pos_contract: Key = self
            .pos_contract_uref
            .expect("should have pos contract uref")
            .into();
        self.query(None, pos_contract, &[])
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
            .and_then(|v| CLValue::try_from(v).ok())
            .and_then(|cl_value| cl_value.into_t().ok())
            .expect("should find balance uref");

        self.query(None, balance_uref, &[])
            .and_then(|v| CLValue::try_from(v).ok())
            .and_then(|cl_value| cl_value.into_t().ok())
            .expect("should parse balance into a U512")
    }

    pub fn get_account(&self, addr: [u8; 32]) -> Option<Account> {
        let account_value = self
            .query(None, Key::Account(addr), &[])
            .expect("should query account");

        if let StoredValue::Account(account) = account_value {
            Some(account)
        } else {
            None
        }
    }

    pub fn get_contract(&self, contract_uref: URef) -> Option<Contract> {
        let contract_value: StoredValue = self
            .query(None, Key::URef(contract_uref), &[])
            .expect("should have contract value");

        if let StoredValue::Contract(contract) = contract_value {
            Some(contract)
        } else {
            None
        }
    }

    pub fn exec_costs(&self, index: usize) -> Vec<Gas> {
        let exec_response = self
            .get_exec_response(index)
            .expect("should have exec response");
        get_exec_costs(exec_response)
    }

    pub fn exec_error_message(&self, index: usize) -> Option<String> {
        let response = self.get_exec_response(index)?;
        let execution_result = get_success_result(&response);
        Some(get_error_message(execution_result))
    }

    pub fn exec_commit_finish(&mut self, execute_request: ExecuteRequest) -> WasmTestResult<S> {
        self.exec(execute_request)
            .expect_success()
            .commit()
            .finish()
    }
}

fn get_relative_path<T: AsRef<Path>>(path: T) -> PathBuf {
    let mut base_path = std::env::current_dir().expect("should get working directory");
    base_path.push(path);
    base_path
}

/// Constructs a default path to WASM files contained in this cargo workspace.
fn get_default_wasm_path() -> PathBuf {
    get_relative_path(COMPILED_WASM_DEFAULT_PATH)
}

/// Constructs a path to TypeScript compiled WASM files
fn get_typescript_wasm_path() -> PathBuf {
    get_relative_path(COMPILED_WASM_TYPESCRIPT_PATH)
}

/// Constructs a list of paths that should be considered while looking for a compiled wasm file.
fn get_compiled_wasm_paths() -> Vec<PathBuf> {
    vec![
        // Contracts compiled with typescript are tried first
        get_typescript_wasm_path(),
        // As a fallback rust contracts are tried
        get_default_wasm_path(),
    ]
}

/// Reads a given compiled contract file based on path
pub fn read_wasm_file_bytes<T: AsRef<Path>>(contract_file: T) -> Vec<u8> {
    lazy_static! {
        static ref WASM_PATHS: Vec<PathBuf> = get_compiled_wasm_paths();
    };

    // Find first path to a given file found in a list of paths
    for wasm_path in WASM_PATHS.iter() {
        let mut filename = wasm_path.clone();
        filename.push(contract_file.as_ref());
        if let Ok(wasm_bytes) = std::fs::read(filename) {
            return wasm_bytes;
        }
    }

    panic!(
        "should read {:?} bytes from disk from possible locations {:?}",
        contract_file.as_ref(),
        &*WASM_PATHS
    );
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

#[allow(clippy::implicit_hasher)]
pub fn create_commit_request(
    prestate_hash: &[u8],
    effects: &AdditiveMap<Key, Transform>,
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
pub fn get_genesis_transforms(genesis_response: &GenesisResponse) -> AdditiveMap<Key, Transform> {
    let commit_transforms: TransformMap = genesis_response
        .get_success()
        .get_effect()
        .get_transform_map()
        .to_vec()
        .try_into()
        .expect("should convert");
    commit_transforms.into_inner()
}

pub fn get_exec_costs(exec_response: &ExecuteResponse) -> Vec<Gas> {
    let deploy_results: &[DeployResult] = exec_response.get_success().get_deploy_results();

    deploy_results
        .iter()
        .map(|deploy_result| {
            let execution_result = deploy_result.get_execution_result();
            let cost = execution_result
                .get_cost()
                .clone()
                .try_into()
                .expect("cost should map to U512");
            Gas::new(cost)
        })
        .collect()
}

#[allow(clippy::implicit_hasher)]
pub fn get_account(transforms: &AdditiveMap<Key, Transform>, account: &Key) -> Option<Account> {
    transforms.get(account).and_then(|transform| {
        if let Transform::Write(StoredValue::Account(account)) = transform {
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

/// Represents the difference between two [`HashMap`]s.
#[derive(Debug, Default, PartialEq, Eq)]
pub struct Diff {
    left: AdditiveMap<Key, Transform>,
    both: AdditiveMap<Key, Transform>,
    right: AdditiveMap<Key, Transform>,
}

impl Diff {
    /// Creates a diff from two [`HashMap`]s.
    pub fn new(left: AdditiveMap<Key, Transform>, right: AdditiveMap<Key, Transform>) -> Diff {
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
    pub fn left(&self) -> &AdditiveMap<Key, Transform> {
        &self.left
    }

    /// Returns the entries that are unique to the `right` input.
    pub fn right(&self) -> &AdditiveMap<Key, Transform> {
        &self.right
    }

    /// Returns the entries shared by both inputs.
    pub fn both(&self) -> &AdditiveMap<Key, Transform> {
        &self.both
    }
}
