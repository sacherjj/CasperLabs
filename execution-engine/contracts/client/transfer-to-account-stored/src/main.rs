#![no_std]
#![no_main]

#[allow(unused_imports)]
use contract;

#[no_mangle]
pub extern "C" fn call() {
    // TODO: new style impl
    // let destination: String = runtime::get_arg(Arg::Destination as u32)
    //     .unwrap_or_revert_with(ApiError::MissingArgument)
    //     .unwrap_or_revert_with(ApiError::InvalidArgument);
    //
    // let key = match destination.as_str() {
    //     DESTINATION_HASH => store_at_hash(),
    //     DESTINATION_UREF => store_at_uref(),
    //     _ => runtime::revert(ApiError::User(Error::UnknownDestination as u16)),
    // };
    // runtime::put_key(CONTRACT_NAME, key);
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
    transfer_to_account::delegate();
}

*/
