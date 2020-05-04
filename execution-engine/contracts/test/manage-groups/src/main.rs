#![no_std]
#![no_main]

#[macro_use]
extern crate alloc;

use alloc::{
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
const CREATE_GROUPS: &str = "create_groups";
const REMOVE_GROUP: &str = "remove_group";
const GROUP_NAME_ARG: &str = "group_name";

#[no_mangle]
pub extern "C" fn create_groups() {
    let metadata_hash_key = runtime::get_key(METADATA_HASH_KEY)
        .unwrap_or_revert()
        .try_into()
        .unwrap();
    let metadata_access_key = runtime::get_key(METADATA_ACCESS_KEY)
        .unwrap_or_revert()
        .try_into()
        .unwrap();

    let uref1 = storage::new_uref(());
    let group_1_urefs = storage::create_contract_user_group(
        metadata_hash_key,
        metadata_access_key,
        "Group 1",
        1,
        BTreeSet::from_iter(vec![uref1]),
    )
    .unwrap_or_revert();
    // Returns new urefs only
    assert_eq!(group_1_urefs.len(), 1);
    assert!(!group_1_urefs.contains(&uref1));
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

/// Restricted uref comes from creating a group and will be assigned to a smart contract
fn create_entrypoints_1() -> BTreeMap<String, EntryPoint> {
    let mut entrypoints = BTreeMap::new();
    let restricted_session = EntryPoint::new(
        Vec::new(),
        CLType::Unit,
        EntryPointAccess::Public,
        EntryPointType::Session,
    );
    entrypoints.insert(CREATE_GROUPS.to_string(), restricted_session);

    let remove_group = EntryPoint::new(
        vec![Parameter::new(GROUP_NAME_ARG, CLType::String)],
        CLType::Unit,
        EntryPointAccess::Public,
        EntryPointType::Session,
    );
    entrypoints.insert(REMOVE_GROUP.to_string(), remove_group);

    entrypoints
}

fn install_version_1(metadata_hash: Key, access_uref: URef) {
    let contract_named_keys = { BTreeMap::new() };

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
    // Session contract
    let (metadata_hash, access_uref) = storage::create_contract_metadata_at_hash();

    runtime::put_key(METADATA_HASH_KEY, metadata_hash);
    runtime::put_key(METADATA_ACCESS_KEY, access_uref.into());

    install_version_1(metadata_hash, access_uref);
}
