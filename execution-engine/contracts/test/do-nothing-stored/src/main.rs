#![no_std]
#![no_main]

extern crate alloc;

use alloc::{collections::BTreeMap, string::ToString, vec::Vec};

use contract::{
    contract_api::{runtime, storage},
    unwrap_or_revert::UnwrapOrRevert,
};
use types::{
    contract_header::{EntryPoint, EntryPointAccess, EntryPointType},
    CLType, Key, SemVer,
};

const ENTRY_FUNCTION_NAME: &str = "delegate";
const DO_NOTHING_HASH_KEY_NAME: &str = "do_nothing_hash";
const DO_NOTHING_ACCESS_KEY_NAME: &str = "do_nothing_access";
const INITIAL_VERSION: SemVer = SemVer::new(1, 0, 0);

#[no_mangle]
pub extern "C" fn delegate() {
    runtime::put_key("called_do_nothing_ver_1", Key::Hash([1u8; 32]));
}

#[no_mangle]
pub extern "C" fn call() {
    let (contract_hash, access_uref) = storage::create_contract_metadata_at_hash();
    runtime::put_key(DO_NOTHING_HASH_KEY_NAME, contract_hash);
    runtime::put_key(DO_NOTHING_ACCESS_KEY_NAME, access_uref.into());

    let methods = {
        let mut methods = BTreeMap::new();

        let delegate = EntryPoint::new(
            Vec::new(),
            CLType::Unit,
            EntryPointAccess::Public,
            EntryPointType::Session,
        );
        methods.insert(ENTRY_FUNCTION_NAME.to_string(), delegate);

        methods
    };

    storage::add_contract_version(
        contract_hash,
        access_uref,
        INITIAL_VERSION,
        methods,
        BTreeMap::new(),
    )
    .unwrap_or_revert();
}
