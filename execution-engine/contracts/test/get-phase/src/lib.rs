#![no_std]

use contract::{contract_api::runtime, unwrap_or_revert::UnwrapOrRevert};
use types::{ApiError, Phase};

#[no_mangle]
pub extern "C" fn call() {
    let known_phase: Phase = runtime::get_arg(0)
        .unwrap_or_revert_with(ApiError::MissingArgument)
        .unwrap_or_revert_with(ApiError::InvalidArgument);
    let get_phase = runtime::get_phase();
    assert_eq!(
        get_phase, known_phase,
        "get_phase did not return known_phase"
    );
}
