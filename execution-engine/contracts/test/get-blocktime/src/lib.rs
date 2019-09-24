#![no_std]
#![feature(cell_update)]

extern crate alloc;
extern crate contract_ffi;

use contract_ffi::contract_api;
use contract_ffi::value::account::BlockTime;

enum Error {
    MissingArgument = 100,
    InvalidArgument = 101,
}

#[no_mangle]
pub extern "C" fn call() {
    let known_block_time: u64 = match contract_api::get_arg(0) {
        Some(Ok(data)) => data,
        Some(Err(_)) => contract_api::revert(Error::InvalidArgument as u32),
        None => contract_api::revert(Error::MissingArgument as u32),
    };
    let actual_block_time: BlockTime = contract_api::get_blocktime();

    assert_eq!(
        actual_block_time,
        BlockTime(known_block_time),
        "actual block time not known block time"
    );
}
