use std::{
    convert::TryInto,
    path::{Path, PathBuf},
};

use lazy_static::lazy_static;

use engine_core::engine_state::genesis::{GenesisAccount, GenesisConfig};
use engine_grpc_server::engine_server::ipc::{
    DeployResult, DeployResult_ExecutionResult, DeployResult_PreconditionFailure, ExecuteResponse,
};
use engine_shared::{
    account::Account, additive_map::AdditiveMap, gas::Gas, stored_value::StoredValue,
    transform::Transform,
};
use types::Key;

use crate::low_level::{
    DEFAULT_CHAIN_NAME, DEFAULT_GENESIS_TIMESTAMP, DEFAULT_PROTOCOL_VERSION, DEFAULT_WASM_COSTS,
    MINT_INSTALL_CONTRACT, POS_INSTALL_CONTRACT,
};

const COMPILED_WASM_DEFAULT_PATH: &str = "../target/wasm32-unknown-unknown/release";
#[cfg(feature = "use-as-wasm")]
const COMPILED_WASM_TYPESCRIPT_PATH: &str = "../target-as";

lazy_static! {
    static ref WASM_PATHS: Vec<PathBuf> = get_compiled_wasm_paths();
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
#[cfg(feature = "use-as-wasm")]
fn get_assembly_script_wasm_path() -> PathBuf {
    get_relative_path(COMPILED_WASM_TYPESCRIPT_PATH)
}

/// Constructs a list of paths that should be considered while looking for a compiled wasm file.
fn get_compiled_wasm_paths() -> Vec<PathBuf> {
    vec![
        // Contracts compiled with typescript are tried first
        #[cfg(feature = "use-as-wasm")]
        get_assembly_script_wasm_path(),
        // As a fallback rust contracts are tried
        get_default_wasm_path(),
    ]
}

/// Reads a given compiled contract file based on path
pub fn read_wasm_file_bytes<T: AsRef<Path>>(contract_file: T) -> Vec<u8> {
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
    let mint_installer_bytes = read_wasm_file_bytes(MINT_INSTALL_CONTRACT);
    let proof_of_stake_installer_bytes = read_wasm_file_bytes(POS_INSTALL_CONTRACT);
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
