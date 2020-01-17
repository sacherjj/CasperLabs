#![no_std]

use contract::{
    contract_api::{account, runtime, system},
    unwrap_or_revert::UnwrapOrRevert,
};
use types::{account::PurseId, ApiError, U512};

const GET_PAYMENT_PURSE: &str = "get_payment_purse";

enum Arg {
    Amount = 0,
}

pub fn delegate() {
    let amount: U512 = runtime::get_arg(Arg::Amount as u32)
        .unwrap_or_revert_with(ApiError::MissingArgument)
        .unwrap_or_revert_with(ApiError::InvalidArgument);

    let main_purse: PurseId = account::get_main_purse();

    let pos_pointer = system::get_proof_of_stake();

    let payment_purse: PurseId = runtime::call_contract(pos_pointer, (GET_PAYMENT_PURSE,));

    system::transfer_from_purse_to_purse(main_purse, payment_purse, amount).unwrap_or_revert();
}

#[cfg(not(feature = "lib"))]
#[no_mangle]
pub extern "C" fn call() {
    delegate();
}
