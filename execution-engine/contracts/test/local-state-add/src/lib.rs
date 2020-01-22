#![no_std]

extern crate alloc;

use alloc::string::String;

use contract::{
    contract_api::{runtime, storage},
    unwrap_or_revert::UnwrapOrRevert,
};
use types::ApiError;

pub const LOCAL_KEY: [u8; 32] = [66u8; 32];

const CMD_WRITE: &str = "write";
const CMD_ADD: &str = "add";

const INITIAL_VALUE: u64 = 10;
const ADD_VALUE: u64 = 5;

#[cfg(not(feature = "lib"))]
#[no_mangle]
pub extern "C" fn call() {
    let command: String = runtime::get_arg(0)
        .unwrap_or_revert_with(ApiError::MissingArgument)
        .unwrap_or_revert_with(ApiError::InvalidArgument);

    if command == CMD_WRITE {
        storage::write_local(LOCAL_KEY, INITIAL_VALUE);
    } else if command == CMD_ADD {
        storage::add_local(LOCAL_KEY, ADD_VALUE);
    }
}
