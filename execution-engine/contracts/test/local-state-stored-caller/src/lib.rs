#![no_std]

use contract::{contract_api::runtime, unwrap_or_revert::UnwrapOrRevert};
use types::{AccessRights, ApiError, ContractRef, URef};

#[repr(u32)]
enum Args {
    LocalStateURef = 0,
}

#[repr(u16)]
enum CustomError {
    MissingLocalStateURefArg = 11,
}

#[no_mangle]
pub extern "C" fn call() {
    let local_state_uref: URef = runtime::get_arg(Args::LocalStateURef as u32)
        .unwrap_or_revert_with(ApiError::User(CustomError::MissingLocalStateURefArg as u16))
        .unwrap_or_revert_with(ApiError::InvalidArgument);

    let local_state_contract_pointer =
        ContractRef::URef(URef::new(local_state_uref.addr(), AccessRights::READ));

    // call do_nothing_stored
    runtime::call_contract(local_state_contract_pointer, ())
}
