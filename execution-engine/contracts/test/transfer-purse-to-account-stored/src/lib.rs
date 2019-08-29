#![no_std]
#![feature(cell_update)]

#[macro_use]
extern crate alloc;
extern crate contract_ffi;

use alloc::collections::BTreeMap;
use alloc::string::String;

use contract_ffi::contract_api;
use contract_ffi::key::Key;
use contract_ffi::value::account::{PublicKey, PurseId};
use contract_ffi::value::U512;

const TRANSFER_PURSE_TO_ACCOUNT_CONTRACT_NAME: &str = "transfer_purse_to_account";
const TRANSFER_FUNCTION_NAME: &str = "transfer";
const TRANSFER_RESULT_UREF_NAME: &str = "transfer_result";
const MAIN_PURSE_FINAL_BALANCE_UREF_NAME: &str = "final_balance";

#[no_mangle]
pub extern "C" fn transfer() {
    let source: PurseId = contract_api::main_purse();
    let destination: PublicKey = contract_api::get_arg(0);
    let amount: U512 = contract_api::get_arg(1);

    let transfer_result = contract_api::transfer_from_purse_to_account(source, destination, amount);

    let final_balance =
        contract_api::get_balance(source).unwrap_or_else(|| contract_api::revert(103));

    let result = format!("{:?}", transfer_result);

    let result_uref: Key = contract_api::new_uref(result).into();
    contract_api::add_uref(TRANSFER_RESULT_UREF_NAME, &result_uref);
    contract_api::add_uref(
        MAIN_PURSE_FINAL_BALANCE_UREF_NAME,
        &contract_api::new_uref(final_balance).into(),
    );
}

#[no_mangle]
pub extern "C" fn call() {
    let known_urefs: BTreeMap<String, Key> = BTreeMap::new();
    let contract = contract_api::fn_by_name(TRANSFER_FUNCTION_NAME, known_urefs);
    let u_ptr = contract_api::new_uref(contract);
    contract_api::add_uref(TRANSFER_PURSE_TO_ACCOUNT_CONTRACT_NAME, &u_ptr.into());
}
