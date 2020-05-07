#![no_std]
#![no_main]

extern crate alloc;

use alloc::{
    collections::BTreeMap,
    string::{String, ToString},
    vec::Vec,
};

use core::convert::TryInto;

use contract::contract_api::{runtime, storage};
use types::{
    contract_header::{EntryPoint, EntryPointAccess, EntryPointType},
    runtime_args, CLType, Key, RuntimeArgs, SemVer, URef,
};

const VERSION_1_0_0: SemVer = SemVer::new(1, 0, 0);
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
    let metadata_hash = runtime::get_key(METADATA_HASH_KEY).expect("should have metadata hash");
    let metadata_ref = Key::Hash(metadata_hash.into_hash().unwrap());
    runtime::call_versioned_contract::<()>(
        metadata_ref,
        SemVer::V1_0_0,
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
    let metadata_hash = runtime::get_key(METADATA_HASH_KEY).expect("should have metadata hash");
    let metadata_ref = Key::Hash(metadata_hash.into_hash().unwrap());

    assert!(runtime::get_key(NEW_KEY).is_none());
    runtime::call_versioned_contract::<()>(
        metadata_ref,
        SemVer::V1_0_0,
        "add_new_key",
        runtime_args! {},
    );
    assert!(runtime::get_key(NEW_KEY).is_some());
}

#[no_mangle]
pub extern "C" fn session_code_caller_as_contract() {
    let metadata_hash: Key = runtime::get_named_arg("metadata_hash");
    runtime::call_versioned_contract::<()>(
        metadata_hash.try_into().unwrap(),
        SemVer::V1_0_0,
        SESSION_CODE,
        runtime_args! {},
    );
}

fn create_entrypoints_1() -> BTreeMap<String, EntryPoint> {
    let mut entrypoints = BTreeMap::new();
    let session_code_test = EntryPoint::new(
        Vec::new(),
        CLType::I32,
        EntryPointAccess::Public,
        EntryPointType::Session,
    );
    entrypoints.insert(SESSION_CODE.to_string(), session_code_test);

    let contract_code_test = EntryPoint::new(
        Vec::new(),
        CLType::I32,
        EntryPointAccess::Public,
        EntryPointType::Contract,
    );
    entrypoints.insert(CONTRACT_CODE.to_string(), contract_code_test);

    let session_code_caller_as_session = EntryPoint::new(
        Vec::new(),
        CLType::I32,
        EntryPointAccess::Public,
        EntryPointType::Session,
    );
    entrypoints.insert(
        "session_code_caller_as_session".to_string(),
        session_code_caller_as_session,
    );

    let session_code_caller_as_contract = EntryPoint::new(
        Vec::new(),
        CLType::I32,
        EntryPointAccess::Public,
        EntryPointType::Contract,
    );
    entrypoints.insert(
        "session_code_caller_as_contract".to_string(),
        session_code_caller_as_contract,
    );

    let add_new_key = EntryPoint::new(
        Vec::new(),
        CLType::I32,
        EntryPointAccess::Public,
        EntryPointType::Session,
    );
    entrypoints.insert("add_new_key".to_string(), add_new_key);
    let add_new_key_as_session = EntryPoint::new(
        Vec::new(),
        CLType::I32,
        EntryPointAccess::Public,
        EntryPointType::Session,
    );
    entrypoints.insert("add_new_key_as_session".to_string(), add_new_key_as_session);

    entrypoints
}

fn install_version_1(metadata_hash: Key, access_uref: URef) -> Key {
    let contract_named_keys = {
        let contract_variable = storage::new_uref(0);

        let mut named_keys = BTreeMap::new();
        named_keys.insert("contract_named_key".to_string(), contract_variable.into());
        named_keys
    };

    let entrypoints = create_entrypoints_1();
    storage::add_contract_version(
        metadata_hash,
        access_uref,
        VERSION_1_0_0,
        entrypoints,
        contract_named_keys,
    )
}

#[no_mangle]
pub extern "C" fn call() {
    // Session contract
    let (contract_hash, access_uref) = storage::create_contract_metadata_at_hash();

    runtime::put_key(METADATA_HASH_KEY, contract_hash);
    runtime::put_key(METADATA_ACCESS_KEY, access_uref.into());
    install_version_1(contract_hash, access_uref);
}
