#![no_std]

extern crate alloc;

extern crate contract_ffi;

use contract_ffi::contract_api::{self, Error};
use contract_ffi::unwrap_or_revert::UnwrapOrRevert;
use contract_ffi::value::account::BlockTime;

#[no_mangle]
pub extern "C" fn call() {
    let known_block_time: u64 = contract_api::runtime::get_arg(0)
        .unwrap_or_revert_with(Error::MissingArgument)
        .unwrap_or_revert_with(Error::InvalidArgument);
    let actual_block_time: BlockTime = contract_api::runtime::get_blocktime();

    assert_eq!(
        actual_block_time,
        BlockTime(known_block_time),
        "actual block time not known block time"
    );
}
