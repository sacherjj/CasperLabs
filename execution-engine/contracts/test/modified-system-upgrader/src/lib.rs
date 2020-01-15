#![no_std]

use contract::{
    contract_api::{runtime, system},
    unwrap_or_revert::UnwrapOrRevert,
};
use types::{ApiError, ContractRef};

#[repr(u16)]
enum CustomError {
    ContractPointerHash = 1,
}

pub const MODIFIED_MINT_EXT_FUNCTION_NAME: &str = "modified_mint_ext";
pub const POS_EXT_FUNCTION_NAME: &str = "pos_ext";

#[no_mangle]
pub extern "C" fn modified_mint_ext() {
    modified_mint::delegate();
}

#[no_mangle]
pub extern "C" fn pos_ext() {
    pos::delegate();
}

fn upgrade_turef(name: &str, contract_ref: ContractRef) {
    let uref = contract_ref
        .into_uref()
        .ok_or(ApiError::User(CustomError::ContractPointerHash as u16))
        .unwrap_or_revert();
    runtime::upgrade_contract_at_uref(name, uref);
}

fn upgrade_mint() {
    let mint_ref = system::get_mint();
    upgrade_turef(MODIFIED_MINT_EXT_FUNCTION_NAME, mint_ref);
}

fn upgrade_proof_of_stake() {
    let pos_ref = system::get_proof_of_stake();
    upgrade_turef(POS_EXT_FUNCTION_NAME, pos_ref);
}

#[no_mangle]
pub extern "C" fn call() {
    upgrade_mint();
    upgrade_proof_of_stake();
}
