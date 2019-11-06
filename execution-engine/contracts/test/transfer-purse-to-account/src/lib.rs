#![no_std]

extern crate alloc;

use alloc::format;

use contract_ffi::contract_api::{account, runtime, storage, system, Error};
use contract_ffi::key::Key;
use contract_ffi::unwrap_or_revert::UnwrapOrRevert;
use contract_ffi::value::account::{PublicKey, PurseId};
use contract_ffi::value::U512;

const TRANSFER_RESULT_UREF_NAME: &str = "transfer_result";
const MAIN_PURSE_FINAL_BALANCE_UREF_NAME: &str = "final_balance";

#[no_mangle]
pub extern "C" fn call() {
    let source: PurseId = account::get_main_purse();
    let destination: PublicKey = runtime::get_arg(0)
        .unwrap_or_revert_with(Error::MissingArgument)
        .unwrap_or_revert_with(Error::InvalidArgument);
    let amount: U512 = runtime::get_arg(1)
        .unwrap_or_revert_with(Error::MissingArgument)
        .unwrap_or_revert_with(Error::InvalidArgument);

    let transfer_result = system::transfer_from_purse_to_account(source, destination, amount);

    let final_balance = system::get_balance(source).unwrap_or_revert_with(Error::User(103));

    let result = format!("{:?}", transfer_result);

    let result_uref: Key = storage::new_turef(result).into();
    runtime::put_key(TRANSFER_RESULT_UREF_NAME, &result_uref);
    runtime::put_key(
        MAIN_PURSE_FINAL_BALANCE_UREF_NAME,
        &storage::new_turef(final_balance).into(),
    );
}
