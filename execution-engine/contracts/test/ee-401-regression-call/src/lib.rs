#![no_std]

extern crate alloc;

use alloc::string::ToString;

use contract::{
    contract_api::{runtime, storage, TURef},
    unwrap_or_revert::UnwrapOrRevert,
};
use types::{ApiError, ContractRef, Key, URef};

#[no_mangle]
pub extern "C" fn call() {
    let contract_key: Key = runtime::get_key("hello_ext").unwrap_or_revert_with(ApiError::GetKey);
    let contract_pointer: ContractRef = match contract_key {
        Key::Hash(hash) => ContractRef::Hash(hash),
        _ => runtime::revert(ApiError::UnexpectedKeyVariant),
    };

    let result: URef = runtime::call_contract(contract_pointer, ());

    let value = storage::read(TURef::from_uref(result).unwrap());

    assert_eq!(Ok(Some("Hello, world!".to_string())), value);
}
