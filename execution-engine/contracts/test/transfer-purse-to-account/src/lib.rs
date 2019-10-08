#![no_std]

#[macro_use]
extern crate alloc;

extern crate contract_ffi;

use contract_ffi::contract_api::{self, Error};
use contract_ffi::key::Key;
use contract_ffi::unwrap_or_revert::UnwrapOrRevert;
use contract_ffi::value::account::{PublicKey, PurseId};
use contract_ffi::value::U512;

const TRANSFER_RESULT_UREF_NAME: &str = "transfer_result";
const MAIN_PURSE_FINAL_BALANCE_UREF_NAME: &str = "final_balance";

#[no_mangle]
pub extern "C" fn call() {
    let source: PurseId = contract_api::main_purse();
    let destination: PublicKey = contract_api::get_arg(0)
        .unwrap_or_revert_with(Error::MissingArgument)
        .unwrap_or_revert_with(Error::InvalidArgument);
    let amount: U512 = contract_api::get_arg(1)
        .unwrap_or_revert_with(Error::MissingArgument)
        .unwrap_or_revert_with(Error::InvalidArgument);

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
