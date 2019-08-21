#![no_std]
#![feature(alloc)]

extern crate cl_std;

use cl_std::contract_api;
use cl_std::execution::Phase;

#[no_mangle]
pub extern "C" fn call() {
    let known_phase: Phase = contract_api::get_arg(0);
    let get_phase = contract_api::get_phase();
    assert_eq!(
        get_phase, known_phase,
        "get_phase did not return known_phase"
    );
}
