#![no_std]

use contract::{
    contract_api::{runtime, ContractRef, Error},
    key::Key,
    unwrap_or_revert::UnwrapOrRevert,
};

const LIST_NAMED_KEYS_KEY: &str = "list_named_keys";

#[no_mangle]
pub extern "C" fn call() {
    let list_named_keys_key =
        runtime::get_key(LIST_NAMED_KEYS_KEY).unwrap_or_revert_with(Error::GetKey);
    let contract_ref = match list_named_keys_key {
        Key::Hash(hash) => ContractRef::Hash(hash),
        _ => runtime::revert(Error::UnexpectedKeyVariant),
    };

    // Call `define` part of the contract.
    runtime::call_contract(contract_ref, ())
}
