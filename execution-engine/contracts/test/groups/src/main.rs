#![no_std]
#![no_main]

#[macro_use]
extern crate alloc;

use alloc::{
    collections::{BTreeMap, BTreeSet},
    string::{String, ToString},
    vec::Vec,
};

use core::convert::TryInto;

use contract::{
    contract_api::{runtime, storage},
    unwrap_or_revert::UnwrapOrRevert,
};
use types::{
    contract_header::{EntryPoint, EntryPointAccess, EntryPointType},
    runtime_args, ApiError, CLType, ContractRef, Key, Parameter, RuntimeArgs, SemVer, URef,
};

const VERSION_1_0_0: SemVer = SemVer::new(1, 0, 0);
const METADATA_HASH_KEY: &str = "metadata_hash_key";
const METADATA_ACCESS_KEY: &str = "metadata_access_key";
const RESTRICTED_CONTRACT: &str = "restricted_contract";
const RESTRICTED_SESSION: &str = "restricted_session";
const RESTRICTED_SESSION_CALLER: &str = "restricted_session_caller";
const UNRESTRICTED_CONTRACT_CALLER: &str = "unrestricted_contract_caller";
const RESTRICTED_CONTRACT_CALLER_AS_SESSION: &str = "restricted_contract_caller_as_session";

#[no_mangle]
pub extern "C" fn restricted_session() {}

#[no_mangle]
pub extern "C" fn restricted_contract() {}

#[no_mangle]
pub extern "C" fn restricted_session_caller() {
    let metadata_hash: Key = runtime::get_named_arg("metadata_hash")
        .unwrap_or_revert_with(ApiError::MissingArgument)
        .unwrap_or_revert_with(ApiError::InvalidArgument);

    let metadata_ref = ContractRef::Hash(metadata_hash.into_hash().unwrap());
    runtime::call_versioned_contract::<()>(
        metadata_ref,
        SemVer::V1_0_0,
        RESTRICTED_SESSION,
        runtime_args! {},
    );
}

fn contract_caller() {
    let metadata_hash: Key = runtime::get_named_arg("metadata_hash")
        .unwrap_or_revert_with(ApiError::MissingArgument)
        .unwrap_or_revert_with(ApiError::InvalidArgument);
    runtime::call_versioned_contract::<()>(
        metadata_hash.try_into().unwrap(),
        SemVer::V1_0_0,
        RESTRICTED_CONTRACT,
        runtime_args! {},
    );
}

#[no_mangle]
pub extern "C" fn unrestricted_contract_caller() {
    contract_caller();
}

#[no_mangle]
pub extern "C" fn restricted_contract_caller_as_session() {
    contract_caller();
}

fn create_group(metadata_hash: Key, access_uref: URef) -> URef {
    let new_uref_1 = storage::new_uref(());
    runtime::put_key("saved_uref", new_uref_1.into());

    let mut existing_urefs = BTreeSet::new();
    existing_urefs.insert(new_uref_1);

    let new_urefs = storage::create_contract_user_group(
        metadata_hash,
        access_uref,
        "Group 1",
        1,
        existing_urefs,
    )
    .unwrap_or_revert();
    assert_eq!(new_urefs.len(), 1);
    new_urefs[0]
}

/// Restricted uref comes from creating a group and will be assigned to a smart contract
fn create_entrypoints_1() -> BTreeMap<String, EntryPoint> {
    let mut entrypoints = BTreeMap::new();
    let restricted_session = EntryPoint::new(
        Vec::new(),
        CLType::I32,
        EntryPointAccess::groups(&["Group 1"]),
        EntryPointType::Session,
    );
    entrypoints.insert(RESTRICTED_SESSION.to_string(), restricted_session);

    let restricted_contract = EntryPoint::new(
        Vec::new(),
        CLType::I32,
        EntryPointAccess::groups(&["Group 1"]),
        EntryPointType::Contract,
    );
    entrypoints.insert(RESTRICTED_CONTRACT.to_string(), restricted_contract);

    let restricted_session_caller = EntryPoint::new(
        vec![Parameter::new("metadata_hash", CLType::Key)],
        CLType::I32,
        EntryPointAccess::Public,
        EntryPointType::Session,
    );
    entrypoints.insert(
        RESTRICTED_SESSION_CALLER.to_string(),
        restricted_session_caller,
    );

    let restricted_contract = EntryPoint::new(
        Vec::new(),
        CLType::I32,
        EntryPointAccess::groups(&["Group 1"]),
        EntryPointType::Contract,
    );
    entrypoints.insert(RESTRICTED_CONTRACT.to_string(), restricted_contract);

    let unrestricted_contract_caller = EntryPoint::new(
        Vec::new(),
        CLType::I32,
        // Made public because we've tested deploy level auth into a contract in
        // RESTRICTED_CONTRACT entrypoint
        EntryPointAccess::Public,
        // NOTE: Public contract authorizes any contract call, because this contract has groups
        // uref in its named keys
        EntryPointType::Contract,
    );
    entrypoints.insert(
        UNRESTRICTED_CONTRACT_CALLER.to_string(),
        unrestricted_contract_caller,
    );

    let unrestricted_contract_caller_as_session = EntryPoint::new(
        Vec::new(),
        CLType::I32,
        // Made public because we've tested deploy level auth into a contract in
        // RESTRICTED_CONTRACT entrypoint
        EntryPointAccess::Public,
        // NOTE: Public contract authorizes any contract call, because this contract has groups
        // uref in its named keys
        EntryPointType::Session,
    );
    entrypoints.insert(
        RESTRICTED_CONTRACT_CALLER_AS_SESSION.to_string(),
        unrestricted_contract_caller_as_session,
    );

    entrypoints
}

fn install_version_1(metadata_hash: Key, access_uref: URef, restricted_uref: URef) {
    let contract_named_keys = {
        let contract_variable = storage::new_uref(0);

        let mut named_keys = BTreeMap::new();
        named_keys.insert("contract_named_key".to_string(), contract_variable.into());
        named_keys.insert("restricted_uref".to_string(), restricted_uref.into());
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
    .unwrap_or_revert();
}

#[no_mangle]
pub extern "C" fn call() {
    // Session contract
    let (contract_hash, access_uref) = storage::create_contract_metadata_at_hash();

    runtime::put_key(METADATA_HASH_KEY, contract_hash);
    runtime::put_key(METADATA_ACCESS_KEY, access_uref.into());

    let restricted_uref = create_group(contract_hash, access_uref);

    install_version_1(contract_hash, access_uref, restricted_uref);
}
