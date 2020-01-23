#![no_std]

extern crate alloc;

use alloc::string::String;

use contract::{
    contract_api::{runtime, system},
    unwrap_or_revert::UnwrapOrRevert,
};
use types::{account::PurseId, ApiError, U512};

const GET_PAYMENT_PURSE: &str = "get_payment_purse";
const SET_REFUND_PURSE: &str = "set_refund_purse";

enum Arg {
    PurseName = 0,
    Amount = 1,
}

#[no_mangle]
pub extern "C" fn call() {
    let purse_name: String = runtime::get_arg(Arg::PurseName as u32)
        .unwrap_or_revert_with(ApiError::MissingArgument)
        .unwrap_or_revert_with(ApiError::InvalidArgument);

    let purse_key = runtime::get_key(&purse_name).unwrap_or_revert_with(ApiError::InvalidPurseName);
    let purse = purse_key
        .as_uref()
        .map(|uref| PurseId::new(*uref))
        .unwrap_or_revert_with(ApiError::InvalidPurse);

    let amount: U512 = runtime::get_arg(Arg::Amount as u32)
        .unwrap_or_revert_with(ApiError::MissingArgument)
        .unwrap_or_revert_with(ApiError::InvalidArgument);

    let pos_pointer = system::get_proof_of_stake();

    let payment_purse: PurseId = runtime::call_contract(pos_pointer.clone(), (GET_PAYMENT_PURSE,));

    runtime::call_contract::<_, ()>(pos_pointer, (SET_REFUND_PURSE, purse));

    system::transfer_from_purse_to_purse(purse, payment_purse, amount).unwrap_or_revert();
}
