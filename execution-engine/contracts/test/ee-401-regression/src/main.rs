#![no_std]
#![no_main]
#![allow(unused_imports)]

extern crate alloc;

use alloc::{collections::BTreeMap, string::String};

use contract::{
    contract_api::{runtime, storage},
    unwrap_or_revert::UnwrapOrRevert,
};
use types::{
    contracts::Parameters, CLType, CLValue, EntryPoint, EntryPointAccess, EntryPointType,
    EntryPoints, URef,
};

#[no_mangle]
pub extern "C" fn hello_ext() {
    let test_string = String::from("Hello, world!");
    let test_uref: URef = storage::new_uref(test_string);
    let return_value = CLValue::from_t(test_uref).unwrap_or_revert();
    runtime::ret(return_value)
}

#[no_mangle]
pub extern "C" fn call() {
    let mut entry_points = {
        let mut entry_points = EntryPoints::new();

        let entry_point = EntryPoint::new(
            "hello_ext",
            Parameters::default(),
            CLType::URef,
            EntryPointAccess::Public,
            EntryPointType::Contract,
        );

        entry_points.add_entry_point(entry_point);

        entry_points
    };
    let mut contract_hash = storage::new_contract(entry_points, None, None, None);
    // let contract_pointer: ContractRef = storage::store_function_at_hash("hello_ext", named_keys);
    runtime::put_key("hello_ext", contract_hash.into());
}
