#![no_std]
#![no_main]

extern crate alloc;

use alloc::{collections::BTreeMap, string::ToString, vec::Vec};
use contract::{
    contract_api::{runtime, storage},
    unwrap_or_revert::UnwrapOrRevert,
};
use core::convert::TryInto;

use types::{
    contract_header::{EntryPoint, EntryPointAccess, EntryPointType},
    CLType, Key, SemVer,
};

const ENTRY_FUNCTION_NAME: &str = "delegate";
const DO_NOTHING_HASH_KEY_NAME: &str = "do_nothing_hash";
const DO_NOTHING_ACCESS_KEY_NAME: &str = "do_nothing_access";
const UPGRADED_VERSION: SemVer = SemVer::new(2, 0, 0);

#[no_mangle]
pub extern "C" fn delegate() {
    runtime::put_key("called_do_nothing_ver_2", Key::Hash([1u8; 32]));
    create_purse_01::delegate()
}

#[no_mangle]
pub extern "C" fn call() {
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

    let do_nothing_hash = runtime::get_key(DO_NOTHING_HASH_KEY_NAME).unwrap_or_revert();
    let do_nothing_uref = runtime::get_key(DO_NOTHING_ACCESS_KEY_NAME)
        .unwrap_or_revert()
        .try_into()
        .unwrap_or_revert();

    let key = storage::add_contract_version(
        do_nothing_hash,
        do_nothing_uref,
        UPGRADED_VERSION,
        methods,
        BTreeMap::new(),
    );
    runtime::put_key("end of upgrade", key);
}
