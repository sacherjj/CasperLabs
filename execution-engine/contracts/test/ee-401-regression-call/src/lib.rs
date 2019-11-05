#![no_std]

extern crate alloc;

use alloc::string::ToString;

use contract_ffi::contract_api::{runtime, storage, ContractRef, Error, TURef};
use contract_ffi::key::Key;
use contract_ffi::unwrap_or_revert::UnwrapOrRevert;
use contract_ffi::uref::URef;

#[no_mangle]
pub extern "C" fn call() {
    let contract_key: Key = runtime::get_key("hello_ext").unwrap_or_revert_with(Error::GetKey);
    let contract_pointer: ContractRef = match contract_key {
        Key::Hash(hash) => ContractRef::Hash(hash),
        _ => runtime::revert(Error::UnexpectedKeyVariant),
    };

    let extra_urefs = [].to_vec();

    let result: URef = runtime::call_contract(contract_pointer, &(), &extra_urefs);

    let value = storage::read(TURef::from_uref(result).unwrap());

    assert_eq!(Ok(Some("Hello, world!".to_string())), value);
}
