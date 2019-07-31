#![no_std]
#![feature(alloc, cell_update)]

extern crate alloc;
extern crate cl_std;

use cl_std::contract_api;
use cl_std::value::account::BlockTime;

#[no_mangle]
pub extern "C" fn call() {
    let known_block_time: u64 = contract_api::get_arg(0);
    let actual_block_time: BlockTime = contract_api::get_blocktime();

    assert_eq!(
        actual_block_time,
        BlockTime(known_block_time),
        "actual block time not known block time"
    );
}
