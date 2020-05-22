#![no_std]
#![no_main]

#[macro_use]
extern crate alloc;

use alloc::collections::BTreeMap;
use contract::{
    contract_api::{runtime, storage, system},
    unwrap_or_revert::UnwrapOrRevert,
};
use types::{contracts::NamedKeys, CLValue, ContractHash, URef};

pub const MODIFIED_MINT_EXT_FUNCTION_NAME: &str = "modified_mint_ext";
pub const POS_EXT_FUNCTION_NAME: &str = "pos_ext";
pub const STANDARD_PAYMENT_FUNCTION_NAME: &str = "pay";

#[no_mangle]
pub extern "C" fn mint() {
    modified_mint::mint();
}

#[no_mangle]
pub extern "C" fn create() {
    modified_mint::create();
}

#[no_mangle]
pub extern "C" fn balance() {
    modified_mint::balance();
}

#[no_mangle]
pub extern "C" fn transfer() {
    modified_mint::transfer();
}

fn upgrade_mint() -> ContractHash {
    const HASH_KEY_NAME: &str = "mint_hash";
    const ACCESS_KEY_NAME: &str = "mint_access";
    runtime::print(&format!(
        "upgrade mint keys: {:?}",
        runtime::list_named_keys()
    ));

    runtime::print(&format!("keys {:?}", runtime::list_named_keys()));

    let mint_package_hash: ContractHash = runtime::get_key(HASH_KEY_NAME)
        .expect("should have mint")
        .into_hash()
        .expect("should be hash");
    let mint_access_key: URef = runtime::get_key(ACCESS_KEY_NAME)
        .unwrap_or_revert()
        .into_uref()
        .expect("shuold be uref");

    let entry_points = modified_mint::get_entry_points();
    let named_keys = NamedKeys::new();
    runtime::print("before upgrading mint");
    storage::add_contract_version(mint_package_hash, mint_access_key, entry_points, named_keys)
}

#[no_mangle]
pub extern "C" fn upgrade() {
    let mut upgrades = BTreeMap::new();

    {
        let old_mint_hash = system::get_mint();
        let new_mint_hash = upgrade_mint();
        upgrades.insert(old_mint_hash, new_mint_hash);
    }

    runtime::ret(CLValue::from_t(upgrades).unwrap());
}
