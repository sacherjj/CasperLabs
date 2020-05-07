#![no_std]
#![no_main]

extern crate alloc;
use alloc::{boxed::Box, collections::BTreeMap, string::ToString, vec};

use contract::{
    contract_api::{runtime, storage},
    unwrap_or_revert::UnwrapOrRevert,
};
use standard_payment::ARG_AMOUNT;
use types::{
    contract_header::{EntryPoint, EntryPointAccess, EntryPointType, Parameter},
    CLType, CLValue, SemVer,
};

const METHOD_PAY: &str = "pay";
const HASH_KEY_NAME: &str = "standard_payment_hash";
const ACCESS_KEY_NAME: &str = "standard_payment_access";

#[no_mangle]
pub extern "C" fn pay() {
    standard_payment::delegate();
}

#[no_mangle]
pub extern "C" fn install() {
    let entry_points = {
        let mut entry_points = BTreeMap::new();

        let entry_point = EntryPoint::new(
            vec![Parameter::new(ARG_AMOUNT, CLType::U512)],
            CLType::Result {
                ok: Box::new(CLType::Unit),
                err: Box::new(CLType::U32),
            },
            EntryPointAccess::Public,
            EntryPointType::Session,
        );
        entry_points.insert(METHOD_PAY.to_string(), entry_point);

        entry_points
    };

    let (contract_metadata_key, access_uref) = storage::create_contract_metadata_at_hash();
    runtime::put_key(HASH_KEY_NAME, contract_metadata_key);
    runtime::put_key(ACCESS_KEY_NAME, access_uref.into());

    let named_keys = BTreeMap::new();
    let version = SemVer::V1_0_0;

    storage::add_contract_version(
        contract_metadata_key,
        access_uref,
        version,
        entry_points,
        named_keys,
    );

    let uref = storage::new_uref(contract_metadata_key);
    let return_value = CLValue::from_t(uref).unwrap_or_revert();
    runtime::ret(return_value);
}
