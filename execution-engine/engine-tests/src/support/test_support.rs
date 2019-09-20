use std::collections::HashMap;
use std::convert::TryInto;
use std::ffi::OsStr;
use std::path::PathBuf;
use std::rc::Rc;
use std::sync::Arc;

use grpc::RequestOptions;
use lmdb::DatabaseFlags;

use contract_ffi::key::Key;
use contract_ffi::value::U512;
use engine_core::engine_state::genesis::GenesisConfig;
use engine_core::engine_state::utils::WasmiBytes;
use engine_core::engine_state::{EngineConfig, EngineState, MAX_PAYMENT, SYSTEM_ACCOUNT_ADDR};
use engine_core::execution::{self, MINT_NAME, POS_NAME};
use engine_grpc_server::engine_server::ipc::{
    CommitRequest, Deploy, DeployCode, DeployResult, DeployResult_ExecutionResult,
    DeployResult_PreconditionFailure, ExecRequest, ExecResponse, GenesisRequest, GenesisResponse,
    QueryRequest,
};
use engine_grpc_server::engine_server::ipc_grpc::ExecutionEngineService;
use engine_grpc_server::engine_server::mappings::{to_domain_validators, CommitTransforms};
use engine_grpc_server::engine_server::state::{BigInt, ProtocolVersion};
use engine_grpc_server::engine_server::{ipc, transforms};
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
use transforms::TransformEntry;
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

pub struct DeployBuilder {
    deploy: Deploy,
}

impl DeployBuilder {
    pub fn new() -> Self {
        Default::default()
    }

    pub fn with_address(mut self, address: [u8; 32]) -> Self {
        self.deploy.set_address(address.to_vec());
        self
    }

    pub fn with_payment_code(
        mut self,
        file_name: &str,
        args: impl contract_ffi::contract_api::argsparser::ArgsParser,
    ) -> Self {
        let wasm_bytes = read_wasm_file_bytes(file_name);
        let args = args
            .parse()
            .and_then(|args_bytes| contract_ffi::bytesrepr::ToBytes::to_bytes(&args_bytes))
            .expect("should serialize args");
        let mut payment = DeployCode::new();
        payment.set_code(wasm_bytes);
        payment.set_args(args);
        self.deploy.set_payment(payment);
        self
    }

    pub fn with_session_code(
        mut self,
        file_name: &str,
        args: impl contract_ffi::contract_api::argsparser::ArgsParser,
    ) -> Self {
        let wasm_bytes = read_wasm_file_bytes(file_name);
        let args = args
            .parse()
            .and_then(|args_bytes| contract_ffi::bytesrepr::ToBytes::to_bytes(&args_bytes))
            .expect("should serialize args");
        let mut session = DeployCode::new();
        session.set_code(wasm_bytes);
        session.set_args(args);
        self.deploy.set_session(session);
        self
    }

    pub fn with_authorization_keys(
        mut self,
        authorization_keys: &[contract_ffi::value::account::PublicKey],
    ) -> Self {
        let authorization_keys = authorization_keys
            .iter()
            .map(|public_key| public_key.value().to_vec())
            .collect();
        self.deploy.set_authorization_keys(authorization_keys);
        self
    }

    pub fn with_deploy_hash(mut self, hash: [u8; 32]) -> Self {
        self.deploy.set_deploy_hash(hash.to_vec());
        self
    }

    pub fn build(self) -> Deploy {
        self.deploy
    }
}

impl Default for DeployBuilder {
    fn default() -> Self {
        let mut deploy = Deploy::new();
        deploy.set_gas_price(1);
        DeployBuilder { deploy }
    }
}

pub struct ExecRequestBuilder {
    deploys: Vec<Deploy>,
    exec_request: ExecRequest,
}

impl ExecRequestBuilder {
    pub fn new() -> Self {
        Default::default()
    }

    pub fn push_deploy(mut self, deploy: Deploy) -> Self {
        self.deploys.push(deploy);
        self
    }

    pub fn with_pre_state_hash(mut self, pre_state_hash: &[u8]) -> Self {
        self.exec_request
            .set_parent_state_hash(pre_state_hash.to_vec());
        self
    }

    pub fn with_block_time(mut self, block_time: u64) -> Self {
        self.exec_request.set_block_time(block_time);
        self
    }

    pub fn with_protocol_version(mut self, version: u64) -> Self {
        let mut protocol_version = ProtocolVersion::new();
        protocol_version.set_value(version);
        self.exec_request.set_protocol_version(protocol_version);
        self
    }

    pub fn build(mut self) -> ExecRequest {
        let mut deploys: protobuf::RepeatedField<Deploy> = <protobuf::RepeatedField<Deploy>>::new();
        for deploy in self.deploys {
            deploys.push(deploy);
        }
        self.exec_request.set_deploys(deploys);
        self.exec_request
    }
}

impl Default for ExecRequestBuilder {
    fn default() -> Self {
        let deploys = vec![];
        let mut exec_request = ExecRequest::new();
        exec_request.set_block_time(DEFAULT_BLOCK_TIME);
        let mut protocol_version = ProtocolVersion::new();
        protocol_version.set_value(1);
        exec_request.set_protocol_version(protocol_version);
        ExecRequestBuilder {
            deploys,
            exec_request,
        }
    }
}

pub fn get_protocol_version() -> ProtocolVersion {
    let mut protocol_version: ProtocolVersion = ProtocolVersion::new();
    protocol_version.set_value(1);
    protocol_version
}

pub fn get_mock_deploy() -> Deploy {
    let mut deploy = Deploy::new();
    deploy.set_address(MOCKED_ACCOUNT_ADDRESS.to_vec());
    deploy.set_gas_price(1);
    let mut deploy_code = DeployCode::new();
    deploy_code.set_code(test_utils::create_empty_wasm_module_bytes());
    deploy.set_session(deploy_code);
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

#[allow(clippy::implicit_hasher)]
pub fn create_genesis_request(
    address: [u8; 32],
    genesis_validators: HashMap<contract_ffi::value::account::PublicKey, contract_ffi::value::U512>,
) -> (GenesisRequest, HashMap<SystemContractType, WasmiBytes>) {
    let genesis_account_addr = address.to_vec();
    let mut contracts: HashMap<SystemContractType, WasmiBytes> = HashMap::new();

    let initial_motes = {
        let mut ret = BigInt::new();
        ret.set_bit_width(512);
        ret.set_value(format!("{}", GENESIS_INITIAL_BALANCE));
        ret
    };

    let mint_code = {
        let mut ret = DeployCode::new();
        let contract_file = "mint_token.wasm";
        let wasm_bytes = read_wasm_file_bytes(contract_file);
        let wasmi_bytes =
            WasmiBytes::new(&wasm_bytes, engine_wasm_prep::wasm_costs::WasmCosts::free())
                .expect("should have wasmi bytes");
        contracts.insert(SystemContractType::Mint, wasmi_bytes);
        ret.set_code(wasm_bytes);
        ret
    };

    let proof_of_stake_code = {
        let mut ret = DeployCode::new();
        let contract_file = "pos.wasm";
        let wasm_bytes = read_wasm_file_bytes(contract_file);
        let wasmi_bytes =
            WasmiBytes::new(&wasm_bytes, engine_wasm_prep::wasm_costs::WasmCosts::free())
                .expect("should have wasmi bytes");
        contracts.insert(SystemContractType::ProofOfStake, wasmi_bytes);
        ret.set_code(wasm_bytes);
        ret
    };

    let grpc_genesis_validators: Vec<ipc::Bond> = genesis_validators
        .iter()
        .map(|(pk, bond)| {
            let mut grpc_bond = ipc::Bond::new();
            grpc_bond.set_validator_public_key(pk.value().to_vec());
            grpc_bond.set_stake((*bond).into());
            grpc_bond
        })
        .collect();

    let protocol_version = {
        let mut ret = ProtocolVersion::new();
        ret.set_value(1);
        ret
    };

    let mut ret = GenesisRequest::new();
    ret.set_address(genesis_account_addr.to_vec());
    ret.set_initial_motes(initial_motes);
    ret.set_mint_code(mint_code);
    ret.set_proof_of_stake_code(proof_of_stake_code);
    ret.set_protocol_version(protocol_version);
    ret.set_genesis_validators(grpc_genesis_validators.into());

    (ret, contracts)
}

pub fn create_query_request(
    post_state: Vec<u8>,
    base_key: &contract_ffi::key::Key,
    path: Vec<String>,
) -> QueryRequest {
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
    payment_args: impl contract_ffi::contract_api::argsparser::ArgsParser,
    session_file: &str,
    session_args: impl contract_ffi::contract_api::argsparser::ArgsParser,
    pre_state_hash: &[u8],
    block_time: u64,
    deploy_hash: [u8; 32],
    authorized_keys: Vec<contract_ffi::value::account::PublicKey>,
) -> ExecRequest {
    let deploy = DeployBuilder::new()
        .with_session_code(session_file, session_args)
        .with_payment_code(payment_file, payment_args)
        .with_address(address)
        .with_authorization_keys(&authorized_keys)
        .with_deploy_hash(deploy_hash)
        .build();

    ExecRequestBuilder::new()
        .with_pre_state_hash(pre_state_hash)
        .with_protocol_version(1)
        .with_block_time(block_time)
        .push_deploy(deploy)
        .build()
}

#[allow(clippy::implicit_hasher)]
pub fn create_commit_request(
    prestate_hash: &[u8],
    effects: &HashMap<contract_ffi::key::Key, Transform>,
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
) -> HashMap<contract_ffi::key::Key, Transform> {
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
) -> Vec<HashMap<contract_ffi::key::Key, Transform>> {
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

pub fn get_exec_costs(exec_response: &ExecResponse) -> Vec<Gas> {
    let deploy_results: &[DeployResult] = exec_response.get_success().get_deploy_results();

    deploy_results
        .iter()
        .map(|deploy_result| Gas::from_u64(deploy_result.get_execution_result().get_cost()))
        .collect()
}

#[allow(clippy::implicit_hasher)]
pub fn get_contract_uref(
    transforms: &HashMap<contract_ffi::key::Key, Transform>,
    contract: Vec<u8>,
) -> Option<contract_ffi::uref::URef> {
    transforms
        .iter()
        .find(|(_, v)| match v {
            Transform::Write(contract_ffi::value::Value::Contract(mint_contract))
                if mint_contract.bytes() == contract.as_slice() =>
            {
                true
            }
            _ => false,
        })
        .and_then(|(k, _)| {
            if let contract_ffi::key::Key::URef(uref) = k {
                Some(*uref)
            } else {
                None
            }
        })
}

#[allow(clippy::implicit_hasher)]
pub fn get_mint_contract_uref(
    transforms: &HashMap<contract_ffi::key::Key, Transform>,
    contracts: &HashMap<SystemContractType, WasmiBytes>,
) -> Option<contract_ffi::uref::URef> {
    let mint_contract_bytes: Vec<u8> = contracts
        .get(&SystemContractType::Mint)
        .map(ToOwned::to_owned)
        .map(Into::into)
        .expect("Should get mint bytes.");

    get_contract_uref(&transforms, mint_contract_bytes)
}

#[allow(clippy::implicit_hasher)]
pub fn get_pos_contract_uref(
    transforms: &HashMap<contract_ffi::key::Key, Transform>,
    contracts: &HashMap<SystemContractType, WasmiBytes>,
) -> Option<contract_ffi::uref::URef> {
    let mint_contract_bytes: Vec<u8> = contracts
        .get(&SystemContractType::ProofOfStake)
        .map(ToOwned::to_owned)
        .map(Into::into)
        .expect("Should get PoS bytes.");

    get_contract_uref(&transforms, mint_contract_bytes)
}

#[allow(clippy::implicit_hasher)]
pub fn get_account(
    transforms: &HashMap<contract_ffi::key::Key, Transform>,
    account: &contract_ffi::key::Key,
) -> Option<contract_ffi::value::Account> {
    transforms.get(account).and_then(|transform| {
        if let Transform::Write(contract_ffi::value::Value::Account(account)) = transform {
            Some(account.to_owned())
        } else {
            None
        }
    })
}

pub fn get_success_result(response: &ExecResponse) -> DeployResult_ExecutionResult {
    let result = response.get_success();

    result
        .get_deploy_results()
        .first()
        .expect("should have a deploy result")
        .get_execution_result()
        .to_owned()
}

pub fn get_precondition_failure(response: &ExecResponse) -> DeployResult_PreconditionFailure {
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
    exec_responses: Vec<ExecResponse>,
    genesis_hash: Option<Vec<u8>>,
    post_state_hash: Option<Vec<u8>>,
    /// Cached transform maps after subsequent successful runs
    /// i.e. transforms[0] is for first run() call etc.
    transforms: Vec<HashMap<contract_ffi::key::Key, Transform>>,
    bonded_validators:
        Vec<HashMap<contract_ffi::value::account::PublicKey, contract_ffi::value::U512>>,
    /// Cached genesis transforms
    genesis_account: Option<contract_ffi::value::Account>,
    /// Genesis transforms
    genesis_transforms: Option<HashMap<contract_ffi::key::Key, Transform>>,
    /// Mint contract uref
    mint_contract_uref: Option<contract_ffi::uref::URef>,
    /// PoS contract uref
    pos_contract_uref: Option<contract_ffi::uref::URef>,
}

impl Default for InMemoryWasmTestBuilder {
    fn default() -> Self {
        let engine_config = EngineConfig::new().set_use_payment_code(true);
        let global_state = InMemoryGlobalState::empty().expect("should create global state");
        let engine_state = EngineState::new(global_state, engine_config);

        WasmTestBuilder {
            engine_state: Rc::new(engine_state),
            exec_responses: Vec::new(),
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

    pub fn run_genesis(
        &mut self,
        genesis_addr: [u8; 32],
        genesis_validators: HashMap<
            contract_ffi::value::account::PublicKey,
            contract_ffi::value::U512,
        >,
    ) -> &mut Self {
        let (genesis_request, contracts) =
            create_genesis_request(genesis_addr, genesis_validators.clone());

        let genesis_response = self
            .engine_state
            .run_genesis(RequestOptions::new(), genesis_request)
            .wait_drop_metadata()
            .unwrap();

        let state_root_hash: Blake2bHash = genesis_response
            .get_success()
            .get_poststate_hash()
            .try_into()
            .unwrap();

        // Cache genesis response transforms for easy access later
        let genesis_transforms = get_genesis_transforms(&genesis_response);

        let mint_contract_uref = get_mint_contract_uref(&genesis_transforms, &contracts)
            .expect("Unable to get mint contract uref");

        let pos_contract_uref = get_pos_contract_uref(&genesis_transforms, &contracts)
            .expect("Unable to get pos contract uref");

        // Cache mint uref
        self.mint_contract_uref = Some(mint_contract_uref);
        self.pos_contract_uref = Some(pos_contract_uref);

        // Cache the account
        self.genesis_account = Some(
            get_account(
                &genesis_transforms,
                &contract_ffi::key::Key::Account(genesis_addr),
            )
            .unwrap_or_else(|| {
                panic!(
                    "Unable to obtain genesis account from genesis response: {:?}",
                    genesis_response
                )
            }),
        );

        let genesis_hash = genesis_response.get_success().get_poststate_hash().to_vec();
        assert_eq!(state_root_hash.to_vec(), genesis_hash);
        self.genesis_hash = Some(genesis_hash.clone());
        // This value will change between subsequent contract executions
        self.post_state_hash = Some(genesis_hash);
        self.bonded_validators.push(genesis_validators);
        self.genesis_transforms = Some(genesis_transforms);
        self
    }

    pub fn run_genesis_with_genesis_config(
        &mut self,
        genesis_config: GenesisConfig,
    ) -> Result<&mut Self, ipc::GenesisDeployError> {
        let system_account = Key::Account(SYSTEM_ACCOUNT_ADDR);
        let genesis_config = genesis_config.try_into().expect("could not parse");

        let genesis_response = self
            .engine_state
            .run_genesis_with_chainspec(RequestOptions::new(), genesis_config)
            .wait_drop_metadata()
            .expect("Unable to get genesis response");

        if genesis_response.has_failed_deploy() {
            return Err(genesis_response.get_failed_deploy().to_owned());
        }

        let state_root_hash: Blake2bHash = genesis_response
            .get_success()
            .get_poststate_hash()
            .try_into()
            .expect("Unable to get root hash");

        let transforms = get_genesis_transforms(&genesis_response);

        let genesis_account =
            get_account(&transforms, &system_account).expect("Unable to get system account");

        let known_keys = genesis_account.urefs_lookup();

        let mint_contract_uref = known_keys
            .get(MINT_NAME)
            .and_then(Key::as_uref)
            .cloned()
            .expect("Unable to get mint contract URef");

        let pos_contract_uref = known_keys
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
        Ok(self)
    }

    pub fn query(
        &self,
        maybe_post_state: Option<Vec<u8>>,
        base_key: contract_ffi::key::Key,
        path: &[&str],
    ) -> Option<contract_ffi::value::Value> {
        let post_state = maybe_post_state
            .or_else(|| self.post_state_hash.clone())
            .expect("builder must have a post-state hash");

        let path_vec: Vec<String> = path.iter().map(|s| String::from(*s)).collect();

        let query_request = create_query_request(post_state, &base_key, path_vec);

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

    pub fn exec_with_exec_request(&mut self, mut exec_request: ExecRequest) -> &mut Self {
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
            .exec(RequestOptions::new(), exec_request)
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
        payment_args: impl contract_ffi::contract_api::argsparser::ArgsParser,
        session_file: &str,
        session_args: impl contract_ffi::contract_api::argsparser::ArgsParser,
        block_time: u64,
        deploy_hash: [u8; 32],
        authorized_keys: Vec<contract_ffi::value::account::PublicKey>,
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
        payment_args: impl contract_ffi::contract_api::argsparser::ArgsParser,
        session_file: &str,
        session_args: impl contract_ffi::contract_api::argsparser::ArgsParser,
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
            vec![contract_ffi::value::account::PublicKey::new(address)],
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
        effects: HashMap<contract_ffi::key::Key, Transform>,
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
            .map(|bond| to_domain_validators(bond).unwrap())
            .collect();
        self.bonded_validators.push(bonded_validators);
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
    pub fn get_transforms(&self) -> Vec<HashMap<contract_ffi::key::Key, Transform>> {
        self.transforms.clone()
    }

    pub fn get_bonded_validators(
        &self,
    ) -> Vec<HashMap<contract_ffi::value::account::PublicKey, contract_ffi::value::U512>> {
        self.bonded_validators.clone()
    }

    /// Gets genesis account (if present)
    pub fn get_genesis_account(&self) -> &contract_ffi::value::Account {
        self.genesis_account
            .as_ref()
            .expect("Unable to obtain genesis account. Please run genesis first.")
    }

    pub fn get_mint_contract_uref(&self) -> contract_ffi::uref::URef {
        self.mint_contract_uref
            .expect("Unable to obtain mint contract uref. Please run genesis first.")
    }

    pub fn get_pos_contract_uref(&self) -> contract_ffi::uref::URef {
        self.pos_contract_uref
            .expect("Unable to obtain pos contract uref. Please run genesis first.")
    }

    pub fn get_genesis_transforms(
        &self,
    ) -> &HashMap<contract_ffi::key::Key, engine_shared::transform::Transform> {
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

    pub fn get_exec_response(&self, index: usize) -> Option<&ExecResponse> {
        self.exec_responses.get(index)
    }

    pub fn finish(&self) -> WasmTestResult<S> {
        WasmTestResult(self.clone())
    }

    pub fn get_pos_contract(&self) -> contract_ffi::value::contract::Contract {
        let genesis_account = self
            .genesis_account
            .clone()
            .expect("should run genesis process first");
        let genesis_key = contract_ffi::key::Key::Account(genesis_account.pub_key());
        let pos_uref: contract_ffi::key::Key = self
            .query(None, genesis_key, &[POS_NAME])
            .and_then(|v| v.try_into().ok())
            .expect("should find PoS URef");

        self.query(None, pos_uref, &[])
            .and_then(|v| v.try_into().ok())
            .expect("should find PoS Contract")
    }

    pub fn get_purse_balance(
        &self,
        purse_id: contract_ffi::value::account::PurseId,
    ) -> contract_ffi::value::uint::U512 {
        let mint = self.get_mint_contract_uref();
        let purse_addr = purse_id.value().addr();
        let purse_bytes = contract_ffi::bytesrepr::ToBytes::to_bytes(&purse_addr)
            .expect("should be able to serialize purse bytes");
        let balance_mapping_key = contract_ffi::key::Key::local(mint.addr(), &purse_bytes);
        let balance_uref = self
            .query(None, balance_mapping_key, &[])
            .and_then(|v| v.try_into().ok())
            .expect("should find balance uref");

        self.query(None, balance_uref, &[])
            .and_then(|v| v.try_into().ok())
            .expect("should parse balance into a U512")
    }

    pub fn get_account(
        &self,
        key: contract_ffi::key::Key,
    ) -> Option<contract_ffi::value::account::Account> {
        let account_value = self.query(None, key, &[]).expect("should query account");

        if let contract_ffi::value::Value::Account(account) = account_value {
            Some(account)
        } else {
            None
        }
    }
}
