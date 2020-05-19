#![no_std]
#![no_main]

extern crate alloc;

use contract::contract_api::{runtime, storage};

use types::{
    contracts::Parameters, ApiError, CLType, ContractHash, EntryPoint, EntryPointAccess,
    EntryPointType, EntryPoints,
};

const ENTRY_POINT_NAME: &str = "revert_test_ext";
const REVERT_TEST_KEY: &str = "revert_test";

#[no_mangle]
pub extern "C" fn revert_test_ext() {
    // Call revert with an application specific non-zero exit code.
    // It is 2 because another contract used by test_revert.py calls revert with 1.
    runtime::revert(ApiError::User(2));
}

fn store() -> ContractHash {
    let entry_points = {
        let mut entry_points = EntryPoints::new();

        let entry_point = EntryPoint::new(
            ENTRY_POINT_NAME,
            Parameters::default(),
            CLType::Unit,
            EntryPointAccess::Public,
            EntryPointType::Contract,
        );

        entry_points.add_entry_point(entry_point);

        entry_points
    };
    storage::new_contract(entry_points, None, None, None)
}

#[no_mangle]
pub extern "C" fn call() {
    let contract_hash = store();
    runtime::put_key(REVERT_TEST_KEY, contract_hash.into());
}
