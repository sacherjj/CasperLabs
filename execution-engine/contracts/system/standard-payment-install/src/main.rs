#![no_std]
#![no_main]

use contract::{
    contract_api::{runtime, storage},
    unwrap_or_revert::UnwrapOrRevert,
};
use types::{ApiError, CLValue};

const PAY_FUNCTION_NAME: &str = "pay";

#[no_mangle]
pub extern "C" fn pay() {
    standard_payment::delegate();
}

#[no_mangle]
pub extern "C" fn call() {
    let uref = storage::store_function(PAY_FUNCTION_NAME, Default::default())
        .into_uref()
        .unwrap_or_revert_with(ApiError::UnexpectedContractRefVariant);

    let return_value = CLValue::from_t(uref).unwrap_or_revert();

    runtime::ret(return_value);
}
