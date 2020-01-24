#![no_std]

use contract::{
    contract_api::{runtime, storage},
    unwrap_or_revert::UnwrapOrRevert,
};
use types::{ApiError, CLValue, URef};

const MINT_FUNCTION_NAME: &str = "mint_ext";

#[no_mangle]
pub extern "C" fn mint_ext() {
    mint_token::delegate();
}

#[no_mangle]
pub extern "C" fn call() {
    let uref: URef = storage::store_function(MINT_FUNCTION_NAME, Default::default())
        .into_uref()
        .unwrap_or_revert_with(ApiError::UnexpectedContractRefVariant);

    let return_value = CLValue::from_t(uref).unwrap_or_revert();

    runtime::ret(return_value);
}
