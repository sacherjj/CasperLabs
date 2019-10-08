#![no_std]
#![feature(cell_update)]

#[macro_use]
extern crate alloc;
extern crate contract_ffi;

use contract_ffi::contract_api::{self, Error};
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
    let destination: PublicKey = match contract_api::get_arg(0) {
        Some(Ok(data)) => data,
        Some(Err(_)) => contract_api::revert(Error::InvalidArgument),
        None => contract_api::revert(Error::MissingArgument),
    };
    let amount: U512 = match contract_api::get_arg(1) {
        Some(Ok(data)) => data,
        Some(Err(_)) => contract_api::revert(Error::InvalidArgument),
        None => contract_api::revert(Error::MissingArgument),
    };

    let transfer_result = contract_api::transfer_from_purse_to_account(source, destination, amount);

    let final_balance =
        contract_api::get_balance(source).unwrap_or_else(|| contract_api::revert(Error::User(103)));

    let result = format!("{:?}", transfer_result);

    let result_uref: Key = contract_api::new_turef(result).into();
    contract_api::put_key(TRANSFER_RESULT_UREF_NAME, &result_uref);
    contract_api::put_key(
        MAIN_PURSE_FINAL_BALANCE_UREF_NAME,
        &contract_api::new_turef(final_balance).into(),
    );
}

#[no_mangle]
pub extern "C" fn call() {
    let key = contract_api::store_function(TRANSFER_FUNCTION_NAME, Default::default())
        .into_turef()
        .unwrap_or_else(|| contract_api::revert(Error::UnexpectedContractPointerVariant))
        .into();

    contract_api::put_key(TRANSFER_PURSE_TO_ACCOUNT_CONTRACT_NAME, &key);
}
