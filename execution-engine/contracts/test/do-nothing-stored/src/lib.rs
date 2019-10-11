#![no_std]

extern crate alloc;
extern crate contract_ffi;

use alloc::collections::BTreeMap;
use alloc::string::String;

use contract_ffi::contract_api::{runtime, storage, system, ContractRef, Error};
use contract_ffi::key::Key;
use contract_ffi::unwrap_or_revert::UnwrapOrRevert;

const MINT_NAME: &str = "mint";
const ENTRY_FUNCTION_NAME: &str = "delegate";
const CONTRACT_NAME: &str = "do_nothing_stored";

#[repr(u16)]
enum CustomError {
    MintHash = 0,
}

#[no_mangle]
pub extern "C" fn delegate() {}

#[no_mangle]
pub extern "C" fn call() {
    let mint_uref = match system::get_mint() {
        ContractRef::Hash(_) => runtime::revert(Error::User(CustomError::MintHash as u16)),
        ContractRef::TURef(turef) => turef.into(),
    };

    let named_keys = {
        let mut tmp = BTreeMap::new();
        tmp.insert(String::from(MINT_NAME), Key::URef(mint_uref));
        tmp
    };

    let key = storage::store_function(ENTRY_FUNCTION_NAME, named_keys)
        .into_turef()
        .unwrap_or_revert_with(Error::UnexpectedContractRefVariant)
        .into();

    runtime::put_key(CONTRACT_NAME, &key);
}
