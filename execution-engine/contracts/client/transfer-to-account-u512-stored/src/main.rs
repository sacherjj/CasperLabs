#![no_std]
#![no_main]

#[allow(unused_imports)]
use contract;

#[no_mangle]
pub extern "C" fn call() {
    // TODO: new style impl
}

/*
extern crate alloc;

use alloc::{collections::BTreeMap, string::String};

use contract::{
    contract_api::{runtime, storage},
    unwrap_or_revert::UnwrapOrRevert,
};
use types::{ApiError, Key};

const CONTRACT_NAME: &str = "transfer_to_account";
const DESTINATION_HASH: &str = "hash";
const DESTINATION_UREF: &str = "uref";
const FUNCTION_NAME: &str = "transfer";

#[repr(u16)]
enum Error {
    UnknownDestination = 1,
}

#[no_mangle]
pub extern "C" fn transfer() {
    transfer_to_account_u512::delegate();
}

#[no_mangle]
pub extern "C" fn call() {

}
*/
