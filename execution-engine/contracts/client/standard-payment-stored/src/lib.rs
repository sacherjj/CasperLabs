#![no_std]

extern crate alloc;

use alloc::{collections::BTreeMap, string::String, vec};

use contract_ffi::{
    contract_api::{account, runtime, storage, system, Error},
    key::Key,
    unwrap_or_revert::UnwrapOrRevert,
    value::{account::PurseId, U512},
};

const GET_PAYMENT_PURSE: &str = "get_payment_purse";
const STANDARD_PAYMENT_CONTRACT_NAME: &str = "standard_payment";
const PAY_FUNCTION_NAME: &str = "pay";

enum Arg {
    Amount = 0,
}

#[no_mangle]
pub extern "C" fn pay() {
    let amount: U512 = runtime::get_arg(Arg::Amount as u32)
        .unwrap_or_revert_with(Error::MissingArgument)
        .unwrap_or_revert_with(Error::InvalidArgument);
    let main_purse: PurseId = account::get_main_purse();

    let pos_pointer = system::get_proof_of_stake();

    let payment_purse: PurseId =
        runtime::call_contract(pos_pointer, &(GET_PAYMENT_PURSE,), &vec![]);

    system::transfer_from_purse_to_purse(main_purse, payment_purse, amount).unwrap_or_revert()
}

#[no_mangle]
pub extern "C" fn call() {
    let named_keys: BTreeMap<String, Key> = BTreeMap::new();
    let pointer = storage::store_function_at_hash(PAY_FUNCTION_NAME, named_keys);
    runtime::put_key(STANDARD_PAYMENT_CONTRACT_NAME, &pointer.into());
}
