#![no_std]
#![no_main]

extern crate alloc;

use alloc::{collections::BTreeMap, string::ToString, vec::Vec};

use contract::contract_api::{runtime, storage};
use types::{
    contracts::{
        EntryPoint, EntryPointAccess, EntryPointType, EntryPoints, CONTRACT_INITIAL_VERSION,
    },
    runtime_args, CLType, ContractHash, ContractPackageHash, Key, RuntimeArgs, URef,
};

const METADATA_HASH_KEY: &str = "metadata_hash_key";
const METADATA_ACCESS_KEY: &str = "metadata_access_key";
const CONTRACT_CODE: &str = "contract_code_test";
const SESSION_CODE: &str = "session_code_test";
const NEW_KEY: &str = "new_key";

#[no_mangle]
pub extern "C" fn session_code_test() {
    assert!(runtime::get_key(METADATA_HASH_KEY).is_some());
    assert!(runtime::get_key(METADATA_ACCESS_KEY).is_some());
    assert!(runtime::get_key("contract_named_key").is_none());
}

#[no_mangle]
pub extern "C" fn contract_code_test() {
    assert!(runtime::get_key(METADATA_HASH_KEY).is_none());
    assert!(runtime::get_key(METADATA_ACCESS_KEY).is_none());

    assert!(runtime::get_key("contract_named_key").is_some());
}

#[no_mangle]
pub extern "C" fn session_code_caller_as_session() {
    let metadata_hash = runtime::get_key(METADATA_HASH_KEY)
        .expect("should have metadata hash")
        .into_seed();
    runtime::call_versioned_contract::<()>(
        metadata_hash,
        CONTRACT_INITIAL_VERSION,
        SESSION_CODE,
        runtime_args! {},
    );
}

#[no_mangle]
pub extern "C" fn add_new_key() {
    let uref = storage::new_uref(());
    runtime::put_key(NEW_KEY, uref.into());
}

#[no_mangle]
pub extern "C" fn add_new_key_as_session() {
    let metadata_hash = runtime::get_key(METADATA_HASH_KEY)
        .expect("should have metadata hash")
        .into_seed();

    assert!(runtime::get_key(NEW_KEY).is_none());
    runtime::call_versioned_contract::<()>(
        metadata_hash,
        CONTRACT_INITIAL_VERSION,
        "add_new_key",
        runtime_args! {},
    );
    assert!(runtime::get_key(NEW_KEY).is_some());
}

#[no_mangle]
pub extern "C" fn session_code_caller_as_contract() {
    let metadata_hash_key: Key = runtime::get_named_arg("metadata_hash");
    let metadata_hash = metadata_hash_key.into_seed();
    runtime::call_versioned_contract::<()>(
        metadata_hash,
        CONTRACT_INITIAL_VERSION,
        SESSION_CODE,
        runtime_args! {},
    );
}

fn create_entrypoints_1() -> EntryPoints {
    let mut entry_points = EntryPoints::new();
    let session_code_test = EntryPoint::new(
        SESSION_CODE.to_string(),
        Vec::new(),
        CLType::I32,
        EntryPointAccess::Public,
        EntryPointType::Session,
    );
    entry_points.add_entry_point(session_code_test);

    let contract_code_test = EntryPoint::new(
        CONTRACT_CODE.to_string(),
        Vec::new(),
        CLType::I32,
        EntryPointAccess::Public,
        EntryPointType::Contract,
    );
    entry_points.add_entry_point(contract_code_test);

    let session_code_caller_as_session = EntryPoint::new(
        "session_code_caller_as_session".to_string(),
        Vec::new(),
        CLType::I32,
        EntryPointAccess::Public,
        EntryPointType::Session,
    );
    entry_points.add_entry_point(session_code_caller_as_session);

    let session_code_caller_as_contract = EntryPoint::new(
        "session_code_caller_as_contract".to_string(),
        Vec::new(),
        CLType::I32,
        EntryPointAccess::Public,
        EntryPointType::Contract,
    );
    entry_points.add_entry_point(session_code_caller_as_contract);

    let add_new_key = EntryPoint::new(
        "add_new_key".to_string(),
        Vec::new(),
        CLType::I32,
        EntryPointAccess::Public,
        EntryPointType::Session,
    );
    entry_points.add_entry_point(add_new_key);
    let add_new_key_as_session = EntryPoint::new(
        "add_new_key_as_session".to_string(),
        Vec::new(),
        CLType::I32,
        EntryPointAccess::Public,
        EntryPointType::Session,
    );
    entry_points.add_entry_point(add_new_key_as_session);

    entry_points
}

fn install_version_1(package_hash: ContractPackageHash, access_uref: URef) -> ContractHash {
    let contract_named_keys = {
        let contract_variable = storage::new_uref(0);

        let mut named_keys = BTreeMap::new();
        named_keys.insert("contract_named_key".to_string(), contract_variable.into());
        named_keys
    };

    let entry_points = create_entrypoints_1();
    storage::add_contract_version(package_hash, access_uref, entry_points, contract_named_keys)
}

#[no_mangle]
pub extern "C" fn call() {
    // Session contract
    let (contract_package_hash, access_uref) = storage::create_contract_package_at_hash();

    runtime::put_key(METADATA_HASH_KEY, contract_package_hash.into());
    runtime::put_key(METADATA_ACCESS_KEY, access_uref.into());
    install_version_1(contract_package_hash, access_uref);
}
