#![no_std]

extern crate alloc;

use alloc::{collections::BTreeMap, string::ToString};

use contract::{
    contract_api::{runtime, storage},
    unwrap_or_revert::UnwrapOrRevert,
};
use types::{ApiError, ContractRef};

const CONTRACT_EXT: &str = "contract_ext";
const CONTRACT_KEY: &str = "contract";

#[no_mangle]
pub extern "C" fn contract_ext() {
    match runtime::get_key(CONTRACT_KEY) {
        Some(key) => {
            // Calls a stored contract if exists.
            runtime::call_contract(key.to_contract_ref().unwrap(), ())
        }
        None => {
            // If given key doesn't exist it's the tail call, and an error is triggered.
            storage::store_function_at_hash("functiondoesnotexist", Default::default());
        }
    }
}

fn install() -> Result<ContractRef, ApiError> {
    let pointer = storage::store_function_at_hash(CONTRACT_EXT, BTreeMap::new());

    let mut keys = BTreeMap::new();
    keys.insert(CONTRACT_KEY.to_string(), pointer.into());
    let pointer = storage::store_function_at_hash(CONTRACT_EXT, keys);

    let mut keys_2 = BTreeMap::new();
    keys_2.insert(CONTRACT_KEY.to_string(), pointer.into());
    let pointer = storage::store_function_at_hash(CONTRACT_EXT, keys_2);

    runtime::put_key(CONTRACT_KEY, pointer.clone().into());

    Ok(pointer)
}

fn dispatch(contract: ContractRef) {
    runtime::call_contract::<_, ()>(contract, ());
}

#[no_mangle]
pub extern "C" fn call() {
    let contract_key = install().unwrap_or_revert();
    dispatch(contract_key)
}
