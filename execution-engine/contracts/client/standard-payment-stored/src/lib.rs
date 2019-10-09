#![no_std]

#[macro_use]
extern crate alloc;

extern crate contract_ffi;

use alloc::collections::BTreeMap;
use alloc::string::String;

use contract_ffi::contract_api::{self, Error};
use contract_ffi::key::Key;
use contract_ffi::unwrap_or_revert::UnwrapOrRevert;
use contract_ffi::value::account::PurseId;
use contract_ffi::value::U512;

const GET_PAYMENT_PURSE: &str = "get_payment_purse";
const STANDARD_PAYMENT_CONTRACT_NAME: &str = "standard_payment";
const PAY_FUNCTION_NAME: &str = "pay";

enum Arg {
    Amount = 0,
}

#[no_mangle]
pub extern "C" fn pay() {
    let amount: U512 = contract_api::get_arg(Arg::Amount as u32)
        .unwrap_or_revert_with(Error::MissingArgument)
        .unwrap_or_revert_with(Error::InvalidArgument);
    let main_purse: PurseId = contract_api::main_purse();

    let pos_pointer = contract_api::get_pos();

    let payment_purse: PurseId =
        contract_api::call_contract(pos_pointer, &(GET_PAYMENT_PURSE,), &vec![]);

    contract_api::transfer_from_purse_to_purse(main_purse, payment_purse, amount).unwrap_or_revert()
}

#[no_mangle]
pub extern "C" fn call() {
    let named_keys: BTreeMap<String, Key> = BTreeMap::new();
    let pointer = contract_api::store_function_at_hash(PAY_FUNCTION_NAME, named_keys);
    contract_api::put_key(STANDARD_PAYMENT_CONTRACT_NAME, &pointer.into());
}
