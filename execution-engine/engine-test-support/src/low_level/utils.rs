use std::{
    path::{Path, PathBuf},
    rc::Rc,
};

use lazy_static::lazy_static;

use engine_core::engine_state::{
    execution_result::ExecutionResult,
    genesis::{GenesisAccount, GenesisConfig},
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

pub fn get_exec_costs<T: AsRef<ExecutionResult>, I: IntoIterator<Item = T>>(
    exec_response: I,
) -> Vec<Gas> {
    exec_response
        .into_iter()
        .map(|res| res.as_ref().cost())
        .collect()
}

pub fn get_success_result(response: &[Rc<ExecutionResult>]) -> &ExecutionResult {
    &*response.get(0).expect("should have a result")
}

pub fn get_precondition_failure(response: &[Rc<ExecutionResult>]) -> String {
    let result = response.get(0).expect("should have a result");
    assert!(
        result.has_precondition_failure(),
        "should be a precondition failure"
    );
    format!("{}", result.error().expect("should have an error"))
}

pub fn get_error_message<T: AsRef<ExecutionResult>, I: IntoIterator<Item = T>>(
    execution_result: I,
) -> String {
    let errors = execution_result
        .into_iter()
        .enumerate()
        .filter_map(|(i, result)| {
            if let ExecutionResult::Failure { error, .. } = result.as_ref() {
                Some(format!("{}: {:?}", i, error))
            } else {
                None
            }
        })
        .collect::<Vec<_>>();
    errors.join("\n")
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
