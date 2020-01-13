#![no_std]

use contract::{
    contract_api::{runtime, Error},
    unwrap_or_revert::UnwrapOrRevert,
    uref::URef,
};

#[repr(u16)]
enum Args {
    LocalStateURef = 0,
}

const ENTRY_FUNCTION_NAME: &str = "upgraded_delegate";

#[no_mangle]
pub extern "C" fn upgraded_delegate() {
    local_state_stored_upgraded::delegate()
}

#[no_mangle]
pub extern "C" fn call() {
    let uref: URef = runtime::get_arg(Args::LocalStateURef as u32)
        .unwrap_or_revert_with(Error::MissingArgument)
        .unwrap_or_revert_with(Error::InvalidArgument);

    // this should overwrite the previous contract obj with the new contract obj at the same uref
    runtime::upgrade_contract_at_uref(ENTRY_FUNCTION_NAME, uref);
}
