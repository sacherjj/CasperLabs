#![no_std]

#[macro_use]
extern crate alloc;

use alloc::{boxed::Box, collections::BTreeMap, string::String};

use contract::{
    contract_api::{runtime, storage},
    unwrap_or_revert::UnwrapOrRevert,
};
use mint::Mint;
use mint_token::{
    MintContract, ARG_AMOUNT, ARG_SOURCE, ARG_TARGET, METHOD_BALANCE, METHOD_CREATE, METHOD_MINT,
    METHOD_TRANSFER,
};
use types::{
    contracts::NamedKeys, system_contract_errors::mint::Error, ApiError, CLType, CLValue,
    EntryPoint, EntryPointAccess, EntryPointType, EntryPoints, Parameter, URef, U512,
};

const VERSION: &str = "1.1.0";
const HASH_KEY_NAME: &str = "mint_hash";
const ACCESS_KEY_NAME: &str = "mint_access";

fn install() {
    let entry_points = mint_token::get_entry_points();

    let (contract_package_hash, access_uref) = storage::create_contract_package_at_hash();
    runtime::put_key(HASH_KEY_NAME, contract_package_hash.into());
    runtime::put_key(ACCESS_KEY_NAME, access_uref.into());

    let named_keys = NamedKeys::new();

    let contract_key =
        storage::add_contract_version(contract_package_hash, access_uref, entry_points, named_keys);

    let return_value = CLValue::from_t((contract_package_hash, contract_key)).unwrap_or_revert();
    runtime::ret(return_value);
}
