#![no_std]

extern crate alloc;

use alloc::{collections::BTreeMap, string::String};

use contract::{
    contract_api::{runtime, storage},
    unwrap_or_revert::UnwrapOrRevert,
};
use types::ApiError;

// This is making use of the undocumented "FFI" function `gas()` which is used by the Wasm
// interpreter to charge gas for upcoming interpreted instructions.  For further info on this, see
// https://docs.rs/pwasm-utils/0.12.0/pwasm_utils/fn.inject_gas_counter.html
extern "C" {
    pub fn gas(amount: i32);
}

const SUBCALL_NAME: &str = "add_gas";
const ADD_GAS_FROM_SESSION: &str = "add-gas-from-session";
const ADD_GAS_VIA_SUBCALL: &str = "add-gas-via-subcall";

enum Args {
    GasAmount = 0,
    MethodName = 1,
}

#[no_mangle]
pub extern "C" fn add_gas() {
    let amount: i32 = runtime::get_arg(Args::GasAmount as u32)
        .unwrap_or_revert_with(ApiError::MissingArgument)
        .unwrap_or_revert_with(ApiError::InvalidArgument);

    unsafe {
        gas(amount);
    }
}

#[no_mangle]
pub extern "C" fn call() {
    let amount: i32 = runtime::get_arg(Args::GasAmount as u32)
        .unwrap_or_revert_with(ApiError::MissingArgument)
        .unwrap_or_revert_with(ApiError::InvalidArgument);

    let method_name: String = runtime::get_arg(Args::MethodName as u32)
        .unwrap_or_revert_with(ApiError::MissingArgument)
        .unwrap_or_revert_with(ApiError::InvalidArgument);

    match method_name.as_str() {
        ADD_GAS_FROM_SESSION => unsafe {
            gas(amount);
        },
        ADD_GAS_VIA_SUBCALL => {
            let reference = storage::store_function_at_hash(SUBCALL_NAME, BTreeMap::new());
            runtime::call_contract::<_, ()>(reference, (amount,));
        }
        _ => runtime::revert(ApiError::InvalidArgument),
    }
}
