#![no_std]

extern crate contract_ffi;

use contract_ffi::contract_api;
use contract_ffi::execution::Phase;

enum Error {
    MissingArg = 100,
    InvalidArgument = 101,
}

#[no_mangle]
pub extern "C" fn call() {
    let known_phase: Phase = contract_api::get_arg(0)
        .unwrap_or_else(|| contract_api::revert(Error::MissingArg as u32))
        .unwrap_or_else(|_| contract_api::revert(Error::InvalidArgument as u32));
    let get_phase = contract_api::get_phase();
    assert_eq!(
        get_phase, known_phase,
        "get_phase did not return known_phase"
    );
}
