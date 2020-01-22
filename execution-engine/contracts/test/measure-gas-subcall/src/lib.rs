#![no_std]

extern crate alloc;

use alloc::{collections::BTreeMap, string::String};

use contract::{
    contract_api::{runtime, storage},
    unwrap_or_revert::UnwrapOrRevert,
};
use types::{ApiError, CLValue, Phase};

#[repr(u16)]
enum CustomError {
    UnexpectedPhaseInline = 0,
    UnexpectedPhaseSub = 1,
}

#[no_mangle]
pub extern "C" fn get_phase_ext() {
    let phase = runtime::get_phase();
    runtime::ret(CLValue::from_t(phase).unwrap_or_revert())
}

#[no_mangle]
pub extern "C" fn noop_ext() {
    runtime::ret(CLValue::from_t(()).unwrap_or_revert())
}

#[no_mangle]
pub extern "C" fn call() {
    const NOOP_EXT: &str = "noop_ext";
    const GET_PHASE_EXT: &str = "get_phase_ext";

    let method_name: String = runtime::get_arg(0)
        .unwrap_or_revert_with(ApiError::MissingArgument)
        .unwrap_or_revert_with(ApiError::InvalidArgument);
    match method_name.as_str() {
        "no-subcall" => {
            let phase = runtime::get_phase();
            if phase != Phase::Session {
                runtime::revert(ApiError::User(CustomError::UnexpectedPhaseInline as u16))
            }
        }
        "do-nothing" => {
            let reference = storage::store_function_at_hash(NOOP_EXT, BTreeMap::new());
            runtime::call_contract::<_, ()>(reference, ());
        }
        "do-something" => {
            let reference = storage::store_function_at_hash(GET_PHASE_EXT, BTreeMap::new());
            let phase: Phase = runtime::call_contract(reference, ());
            if phase != Phase::Session {
                runtime::revert(ApiError::User(CustomError::UnexpectedPhaseSub as u16))
            }
        }
        _ => {}
    }
}
