#![no_std]
#![no_main]

use contract::contract_api::{runtime, system};
use types::{ApiError, Key};

#[repr(u16)]
enum CustomError {
    ContractPointerHash = 1,
    UnexpectedKeyVariant = 2,
}

pub const EXT_FUNCTION_NAME: &str = "modified_mint_ext";

#[no_mangle]
pub extern "C" fn modified_mint_ext() {
    modified_mint::delegate();
}

#[no_mangle]
pub extern "C" fn call() {
    let mint_pointer = system::get_mint();

    let mint_uref = match mint_pointer {
        Key::URef(uref) => uref,
        Key::Hash(_) => runtime::revert(ApiError::User(CustomError::ContractPointerHash as u16)),
        _ => runtime::revert(ApiError::User(CustomError::UnexpectedKeyVariant as u16)),
    };

    runtime::upgrade_contract_at_uref(EXT_FUNCTION_NAME, mint_uref);
}
