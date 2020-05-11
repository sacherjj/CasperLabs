#![no_std]
#![no_main]

extern crate alloc;

use alloc::{boxed::Box, collections::BTreeMap, string::ToString, vec};
use contract::{
    contract_api::{runtime, storage},
    unwrap_or_revert::UnwrapOrRevert,
};
use mint_token::{
    ARG_AMOUNT, ARG_PURSE, ARG_SOURCE, ARG_TARGET, METHOD_BALANCE, METHOD_CREATE, METHOD_MINT,
    METHOD_TRANSFER,
};
use types::{
    contracts::{EntryPoint, EntryPointAccess, EntryPointType, Parameter},
    CLType, CLValue, SemVer,
};

const HASH_KEY_NAME: &str = "mint_hash";
const ACCESS_KEY_NAME: &str = "mint_access";

#[no_mangle]
pub extern "C" fn mint() {
    mint_token::mint();
}

#[no_mangle]
pub extern "C" fn create() {
    mint_token::create();
}

#[no_mangle]
pub extern "C" fn balance() {
    mint_token::balance();
}

#[no_mangle]
pub extern "C" fn transfer() {
    mint_token::transfer();
}

#[no_mangle]
pub extern "C" fn install() {
    let entry_points = {
        let mut entry_points = BTreeMap::new();

        let entry_point = EntryPoint::new(
            vec![Parameter::new(ARG_AMOUNT, CLType::U512)],
            CLType::Result {
                ok: Box::new(CLType::URef),
                err: Box::new(CLType::U8),
            },
            EntryPointAccess::Public,
            EntryPointType::Contract,
        );
        entry_points.insert(METHOD_MINT.to_string(), entry_point);

        let entry_point = EntryPoint::new(
            vec![],
            CLType::URef,
            EntryPointAccess::Public,
            EntryPointType::Contract,
        );
        entry_points.insert(METHOD_CREATE.to_string(), entry_point);

        let entry_point = EntryPoint::new(
            vec![Parameter::new(ARG_PURSE, CLType::URef)],
            CLType::Option(Box::new(CLType::U512)),
            EntryPointAccess::Public,
            EntryPointType::Contract,
        );
        entry_points.insert(METHOD_BALANCE.to_string(), entry_point);

        let entry_point = EntryPoint::new(
            vec![
                Parameter::new(ARG_SOURCE, CLType::URef),
                Parameter::new(ARG_TARGET, CLType::URef),
                Parameter::new(ARG_AMOUNT, CLType::U512),
            ],
            CLType::Result {
                ok: Box::new(CLType::Unit),
                err: Box::new(CLType::U8),
            },
            EntryPointAccess::Public,
            EntryPointType::Contract,
        );
        entry_points.insert(METHOD_TRANSFER.to_string(), entry_point);

        entry_points
    };

    let (contract_metadata_key, access_uref) = storage::create_contract_metadata_at_hash();
    runtime::put_key(HASH_KEY_NAME, contract_metadata_key);
    runtime::put_key(ACCESS_KEY_NAME, access_uref.into());

    let named_keys = BTreeMap::new();
    let version = SemVer::V1_0_0;

    let contract_key = storage::add_contract_version(
        contract_metadata_key,
        access_uref,
        version,
        entry_points,
        named_keys,
    );

    let return_value = CLValue::from_t((contract_metadata_key, contract_key)).unwrap_or_revert();
    runtime::ret(return_value);
}
