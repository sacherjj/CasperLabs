#![no_std]

extern crate alloc;
extern crate contract_ffi;

use alloc::collections::BTreeMap;
use alloc::string::String;
use contract_ffi::contract_api::pointers::ContractPointer;
use contract_ffi::contract_api::{self, Error};
use contract_ffi::key::Key;

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
    let mint_uref = match contract_api::get_mint() {
        ContractPointer::Hash(_) => {
            contract_api::revert(Error::User(CustomError::MintHash as u16).into())
        }
        ContractPointer::URef(turef) => turef.into(),
    };

    let mint_key = Key::URef(mint_uref);

    let mut known_urefs: BTreeMap<String, Key> = BTreeMap::new();
    known_urefs.insert(String::from(MINT_NAME), mint_key);
    let contract = contract_api::fn_by_name(ENTRY_FUNCTION_NAME, known_urefs);
    let key = contract_api::new_turef(contract).into();
    contract_api::add_uref(CONTRACT_NAME, &key);
}
