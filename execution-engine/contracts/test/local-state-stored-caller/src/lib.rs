#![no_std]

extern crate alloc;

use alloc::vec;

use contract_ffi::{
    contract_api::{runtime, ContractRef, Error},
    unwrap_or_revert::UnwrapOrRevert,
    uref::{AccessRights, URef},
};

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
        .unwrap_or_revert_with(Error::User(CustomError::MissingLocalStateURefArg as u16))
        .unwrap_or_revert_with(Error::InvalidArgument);

    let local_state_contract_pointer =
        ContractRef::URef(URef::new(local_state_uref.addr(), AccessRights::READ));

    // call do_nothing_stored
    runtime::call_contract::<_, ()>(local_state_contract_pointer, (), vec![]);
}
