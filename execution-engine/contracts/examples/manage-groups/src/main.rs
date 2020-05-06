#![no_std]
#![no_main]

#[macro_use]
extern crate alloc;

use alloc::{
    boxed::Box,
    collections::{BTreeMap, BTreeSet},
    string::{String, ToString},
    vec::Vec,
};

use core::{convert::TryInto, iter::FromIterator};

use contract::{
    contract_api::{runtime, storage},
    unwrap_or_revert::UnwrapOrRevert,
};
use types::{
    contract_header::{EntryPoint, EntryPointAccess, EntryPointType},
    ApiError, CLType, Key, Parameter, SemVer, URef,
};

const METADATA_HASH_KEY: &str = "metadata_hash_key";
const METADATA_ACCESS_KEY: &str = "metadata_access_key";
const CREATE_GROUP: &str = "create_group";
const REMOVE_GROUP: &str = "remove_group";
const EXTEND_GROUP_UREFS: &str = "extend_group_urefs";
const REMOVE_GROUP_UREFS: &str = "remove_group_urefs";
const GROUP_NAME_ARG: &str = "group_name";
const UREFS_ARG: &str = "urefs";
const TOTAL_NEW_UREFS_ARG: &str = "total_new_urefs";
const TOTAL_EXISTING_UREFS_ARG: &str = "total_existing_urefs";

#[no_mangle]
pub extern "C" fn create_group() {
    let metadata_hash_key = runtime::get_key(METADATA_HASH_KEY)
        .unwrap_or_revert()
        .try_into()
        .unwrap();
    let metadata_access_key = runtime::get_key(METADATA_ACCESS_KEY)
        .unwrap_or_revert()
        .try_into()
        .unwrap();
    let group_name: String = runtime::get_named_arg(GROUP_NAME_ARG)
        .unwrap_or_revert_with(ApiError::MissingArgument)
        .unwrap_or_revert_with(ApiError::InvalidArgument);
    let total_urefs: u64 = runtime::get_named_arg(TOTAL_NEW_UREFS_ARG)
        .unwrap_or_revert_with(ApiError::MissingArgument)
        .unwrap_or_revert_with(ApiError::InvalidArgument);
    let total_existing_urefs: u64 = runtime::get_named_arg(TOTAL_EXISTING_UREFS_ARG)
        .unwrap_or_revert_with(ApiError::MissingArgument)
        .unwrap_or_revert_with(ApiError::InvalidArgument);
    let existing_urefs: Vec<URef> = (0..total_existing_urefs).map(storage::new_uref).collect();

    let _new_urefs = storage::create_contract_user_group(
        metadata_hash_key,
        metadata_access_key,
        &group_name,
        total_urefs as u8,
        BTreeSet::from_iter(existing_urefs),
    )
    .unwrap_or_revert();
}

#[no_mangle]
pub extern "C" fn remove_group() {
    let metadata_hash_key: Key = runtime::get_key(METADATA_HASH_KEY)
        .unwrap_or_revert()
        .try_into()
        .unwrap();
    let metadata_access_key: URef = runtime::get_key(METADATA_ACCESS_KEY)
        .unwrap_or_revert()
        .try_into()
        .unwrap();
    let group_name: String = runtime::get_named_arg(GROUP_NAME_ARG)
        .unwrap_or_revert_with(ApiError::MissingArgument)
        .unwrap_or_revert_with(ApiError::InvalidArgument);
    storage::remove_contract_user_group(metadata_hash_key, metadata_access_key, &group_name)
        .unwrap_or_revert();
}

#[no_mangle]
pub extern "C" fn extend_group_urefs() {
    let metadata_hash_key: Key = runtime::get_key(METADATA_HASH_KEY)
        .unwrap_or_revert()
        .try_into()
        .unwrap();
    let metadata_access_key: URef = runtime::get_key(METADATA_ACCESS_KEY)
        .unwrap_or_revert()
        .try_into()
        .unwrap();
    let group_name: String = runtime::get_named_arg(GROUP_NAME_ARG)
        .unwrap_or_revert_with(ApiError::MissingArgument)
        .unwrap_or_revert_with(ApiError::InvalidArgument);
    let new_urefs_count: u64 = runtime::get_named_arg(TOTAL_NEW_UREFS_ARG)
        .unwrap_or_revert_with(ApiError::MissingArgument)
        .unwrap_or_revert_with(ApiError::InvalidArgument);

    // Creates 1 additional uref inside group
    let new_urefs = storage::extend_contract_user_group_urefs(
        metadata_hash_key,
        metadata_access_key,
        &group_name,
        new_urefs_count as usize,
    )
    .unwrap_or_revert();
    assert_eq!(new_urefs.len(), new_urefs_count as usize);
    let _uref = new_urefs.iter().next().expect("should get first uref");
}

#[no_mangle]
pub extern "C" fn remove_group_urefs() {
    let metadata_hash_key: Key = runtime::get_key(METADATA_HASH_KEY)
        .unwrap_or_revert()
        .try_into()
        .unwrap();
    let metadata_access_key: URef = runtime::get_key(METADATA_ACCESS_KEY)
        .unwrap_or_revert()
        .try_into()
        .unwrap();
    let group_name: String = runtime::get_named_arg(GROUP_NAME_ARG)
        .unwrap_or_revert_with(ApiError::MissingArgument)
        .unwrap_or_revert_with(ApiError::InvalidArgument);
    let urefs: Vec<URef> = runtime::get_named_arg(UREFS_ARG)
        .unwrap_or_revert_with(ApiError::MissingArgument)
        .unwrap_or_revert_with(ApiError::InvalidArgument);
    storage::remove_contract_user_group_urefs(
        metadata_hash_key,
        metadata_access_key,
        &group_name,
        BTreeSet::from_iter(urefs),
    )
    .unwrap_or_revert();
}

/// Restricted uref comes from creating a group and will be assigned to a smart contract
fn create_entrypoints_1() -> BTreeMap<String, EntryPoint> {
    let mut entrypoints = BTreeMap::new();
    let restricted_session = EntryPoint::new(
        vec![
            Parameter::new(GROUP_NAME_ARG, CLType::String),
            Parameter::new(TOTAL_EXISTING_UREFS_ARG, CLType::U64),
            Parameter::new(TOTAL_NEW_UREFS_ARG, CLType::U64),
        ],
        CLType::Unit,
        EntryPointAccess::Public,
        EntryPointType::Session,
    );
    entrypoints.insert(CREATE_GROUP.to_string(), restricted_session);

    let remove_group = EntryPoint::new(
        vec![Parameter::new(GROUP_NAME_ARG, CLType::String)],
        CLType::Unit,
        EntryPointAccess::Public,
        EntryPointType::Session,
    );
    entrypoints.insert(REMOVE_GROUP.to_string(), remove_group);

    let extend_group_urefs = EntryPoint::new(
        vec![
            Parameter::new(GROUP_NAME_ARG, CLType::String),
            Parameter::new(TOTAL_NEW_UREFS_ARG, CLType::U64),
        ],
        CLType::Unit,
        EntryPointAccess::Public,
        EntryPointType::Session,
    );
    entrypoints.insert(EXTEND_GROUP_UREFS.to_string(), extend_group_urefs);

    let remove_group_urefs = EntryPoint::new(
        vec![
            Parameter::new(GROUP_NAME_ARG, CLType::String),
            Parameter::new(UREFS_ARG, CLType::List(Box::new(CLType::URef))),
        ],
        CLType::Unit,
        EntryPointAccess::Public,
        EntryPointType::Session,
    );
    entrypoints.insert(REMOVE_GROUP_UREFS.to_string(), remove_group_urefs);
    entrypoints
}

fn install_version_1(metadata_hash: Key, access_uref: URef) {
    let contract_named_keys = BTreeMap::new();

    let entrypoints = create_entrypoints_1();
    storage::add_contract_version(
        metadata_hash,
        access_uref,
        SemVer::V1_0_0,
        entrypoints,
        contract_named_keys,
    )
    .unwrap_or_revert();
}

#[no_mangle]
pub extern "C" fn call() {
    let (metadata_hash, access_uref) = storage::create_contract_metadata_at_hash();

    runtime::put_key(METADATA_HASH_KEY, metadata_hash);
    runtime::put_key(METADATA_ACCESS_KEY, access_uref.into());

    install_version_1(metadata_hash, access_uref);
}
