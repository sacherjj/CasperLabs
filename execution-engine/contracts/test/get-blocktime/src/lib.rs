#![no_std]

use contract::{contract_api::runtime, unwrap_or_revert::UnwrapOrRevert};
use types::{ApiError, BlockTime};

#[no_mangle]
pub extern "C" fn call() {
    let known_block_time: u64 = runtime::get_arg(0)
        .unwrap_or_revert_with(ApiError::MissingArgument)
        .unwrap_or_revert_with(ApiError::InvalidArgument);
    let actual_block_time: BlockTime = runtime::get_blocktime();

    assert_eq!(
        actual_block_time,
        BlockTime::new(known_block_time),
        "actual block time not known block time"
    );
}
