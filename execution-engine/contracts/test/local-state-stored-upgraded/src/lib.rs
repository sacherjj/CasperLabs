#![no_std]
#![allow(unused_imports)]
//#![feature(cell_update)]

extern crate alloc;
extern crate contract_ffi;
extern crate local_state;

use alloc::collections::BTreeMap;
use alloc::string::String;
use contract_ffi::contract_api;
use contract_ffi::key::Key;

pub const ENTRY_FUNCTION_NAME: &str = "delegate";
pub const CONTRACT_NAME: &str = "local_state_stored";
pub const SNIPPET: &str = " I've been upgraded!";

#[repr(u16)]
enum CustomError {
    UnableToReadMutatedLocalKey = 0,
    LocalKeyReadMutatedBytesRepr = 1,
}

#[no_mangle]
pub extern "C" fn delegate() {
    local_state::delegate();
    // read from local state
    let mut res: String = contract_api::read_local(local_state::LOCAL_KEY)
        .unwrap_or_default()
        .unwrap_or_default();

    res.push_str(SNIPPET);
    // Write "Hello, "
    contract_api::write_local(local_state::LOCAL_KEY, res);

    // Read back
    let res: String = contract_api::read_local(local_state::LOCAL_KEY)
        .unwrap_or_else(|_| {
            contract_api::revert(
                contract_api::Error::User(CustomError::UnableToReadMutatedLocalKey as u16).into(),
            )
        })
        .unwrap_or_else(|| {
            contract_api::revert(
                contract_api::Error::User(CustomError::LocalKeyReadMutatedBytesRepr as u16).into(),
            )
        });

    // local state should be available after upgrade
    assert!(
        !res.is_empty(),
        "local value should be accessible post upgrade"
    )
}

#[cfg(not(feature = "lib"))]
#[no_mangle]
pub extern "C" fn call() {
    let known_urefs: BTreeMap<String, Key> = BTreeMap::new();
    let contract = contract_api::fn_by_name(ENTRY_FUNCTION_NAME, known_urefs);
    let key = contract_api::new_turef(contract).into();
    contract_api::add_uref(CONTRACT_NAME, &key);
}
