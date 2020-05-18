#![no_std]
#![no_main]

#[macro_use]
extern crate alloc;

use alloc::{string::ToString, vec::Vec};

use alloc::boxed::Box;
use contract::{
    contract_api::{runtime, storage},
    unwrap_or_revert::UnwrapOrRevert,
};
use types::{
    account::PublicKey, CLType, CLValue, EntryPoint, EntryPointAccess, EntryPointType, EntryPoints,
    RuntimeArgs,
};

const ENTRY_POINT_NAME: &str = "get_caller_ext";
const HASH_KEY_NAME: &str = "caller_subcall";
const ACCESS_KEY_NAME: &str = "caller_subcall_access";
const ARG_ACCOUNT: &str = "account";

#[no_mangle]
pub extern "C" fn get_caller_ext() {
    let caller_public_key: PublicKey = runtime::get_caller();
    runtime::ret(CLValue::from_t(caller_public_key).unwrap_or_revert());
}

#[no_mangle]
pub extern "C" fn call() {
    let known_public_key: PublicKey = runtime::get_named_arg(ARG_ACCOUNT);
    let caller_public_key: PublicKey = runtime::get_caller();
    assert_eq!(
        caller_public_key, known_public_key,
        "caller public key was not known public key"
    );

    let entry_points = {
        let mut entry_points = EntryPoints::new();
        // takes no args, ret's PublicKey
        let entry_point = EntryPoint::new(
            ENTRY_POINT_NAME.to_string(),
            Vec::new(),
            CLType::FixedList(Box::new(CLType::U8), 32),
            EntryPointAccess::Public,
            EntryPointType::Contract,
        );
        entry_points.add_entry_point(entry_point);
        entry_points
    };

    let contract_hash = storage::new_contract(
        entry_points,
        None,
        Some(HASH_KEY_NAME.to_string()),
        Some(ACCESS_KEY_NAME.to_string()),
    );

    let subcall_public_key: PublicKey =
        runtime::call_contract(contract_hash, ENTRY_POINT_NAME, RuntimeArgs::default());
    assert_eq!(
        subcall_public_key, known_public_key,
        "subcall public key was not known public key"
    );
}
