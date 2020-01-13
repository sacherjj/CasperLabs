#![no_std]

use contract::{
    contract_api::{runtime, Error},
    execution::Phase,
    unwrap_or_revert::UnwrapOrRevert,
};

#[no_mangle]
pub extern "C" fn call() {
    let known_phase: Phase = runtime::get_arg(0)
        .unwrap_or_revert_with(Error::MissingArgument)
        .unwrap_or_revert_with(Error::InvalidArgument);
    let get_phase = runtime::get_phase();
    assert_eq!(
        get_phase, known_phase,
        "get_phase did not return known_phase"
    );
}
