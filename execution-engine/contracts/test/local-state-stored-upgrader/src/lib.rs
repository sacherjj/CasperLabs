#![no_std]

use contract_ffi::contract_api::{runtime, Error, TURef};
use contract_ffi::unwrap_or_revert::UnwrapOrRevert;
use contract_ffi::uref::URef;

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

    let turef = TURef::from_uref(uref).unwrap_or_revert();

    // this should overwrite the previous contract obj with the new contract obj at the same uref
    runtime::upgrade_contract_at_uref(ENTRY_FUNCTION_NAME, turef);
}
