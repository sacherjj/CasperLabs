#![no_std]

extern crate alloc;

use alloc::string::{String, ToString};
use alloc::vec;
use core::clone::Clone;
use core::convert::Into;

use contract_ffi::contract_api::{runtime, storage, TURef};
use contract_ffi::key::Key;
use contract_ffi::uref::{AccessRights, URef};

const DATA: &str = "data";
const CONTRACT_NAME: &str = "create";

#[no_mangle]
pub extern "C" fn create() {
    let reference: TURef<String> = storage::new_turef(DATA.to_string());

    let read_only_reference: URef = {
        let mut ret: TURef<String> = reference.clone();
        ret.set_access_rights(AccessRights::READ);
        ret.into()
    };

    let extra_urefs = vec![read_only_reference];

    runtime::ret(read_only_reference, extra_urefs)
}

#[no_mangle]
pub extern "C" fn call() {
    let contract: Key = storage::store_function_at_hash(CONTRACT_NAME, Default::default()).into();
    runtime::put_key(CONTRACT_NAME, &contract)
}
