#![no_std]
#![no_main]

extern crate alloc;

use alloc::{collections::BTreeMap, string::ToString, vec};

use contract::{contract_api::storage, unwrap_or_revert::UnwrapOrRevert};
use types::{
    contract_header::{EntryPoint, EntryPointAccess, EntryPointType, Parameter},
    CLType,
};

const ENTRY_FUNCTION_NAME: &str = "pay";
const HASH_KEY_NAME: &str = "test_payment_hash";
const ACCESS_KEY_NAME: &str = "test_payment_access";
const ARG_NAME: &str = "amount";

#[no_mangle]
pub extern "C" fn pay() {
    standard_payment::delegate();
}

#[no_mangle]
pub extern "C" fn call() {
    let methods = {
        let mut methods = BTreeMap::new();
        let entry_point = EntryPoint::new(
            vec![Parameter::new(ARG_NAME, CLType::U512)],
            CLType::Unit,
            EntryPointAccess::Public,
            EntryPointType::Session,
        );
        methods.insert(ENTRY_FUNCTION_NAME.to_string(), entry_point);
        methods
    };

    storage::new_contract(
        methods,
        None,
        Some(HASH_KEY_NAME.to_string()),
        Some(ACCESS_KEY_NAME.to_string()),
    )
    .unwrap_or_revert();
}
