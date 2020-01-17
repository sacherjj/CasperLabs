#![no_std]

#[macro_use]
extern crate alloc;

extern crate contract;

use contract::{
    contract_api::{account, runtime, system},
    unwrap_or_revert::UnwrapOrRevert,
};
use types::{ApiError, U512};

enum Arg {
    TotalPurses = 0,
    SeedAmount,
}

#[no_mangle]
pub extern "C" fn call() {
    let total_purses: u64 = runtime::get_arg(Arg::TotalPurses as u32)
        .unwrap_or_revert_with(ApiError::MissingArgument)
        .unwrap_or_revert_with(ApiError::InvalidArgument);
    let seed_amount: U512 = runtime::get_arg(Arg::SeedAmount as u32)
        .unwrap_or_revert_with(ApiError::MissingArgument)
        .unwrap_or_revert_with(ApiError::InvalidArgument);

    for i in 0..total_purses {
        let new_purse = system::create_purse();
        system::transfer_from_purse_to_purse(account::get_main_purse(), new_purse, seed_amount)
            .unwrap_or_revert();

        let name = format!("purse:{}", i);
        runtime::put_key(&name, new_purse.value().into());
    }
}
