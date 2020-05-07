#![no_std]
#![no_main]

use contract::{
    contract_api::{account, runtime, system},
    unwrap_or_revert::UnwrapOrRevert,
};
use types::{ApiError, Key, URef, U512};

const POS_BOND: &str = "bond";
const POS_UNBOND: &str = "unbond";

fn bond(contract_key: Key, amount: U512, source: URef) {
    runtime::call_contract::<_, ()>(contract_key, (POS_BOND, amount, source));
}

fn unbond(contract_key: Key, amount: Option<U512>) {
    runtime::call_contract::<_, ()>(contract_key, (POS_UNBOND, amount));
}

#[no_mangle]
pub extern "C" fn call() {
    let contract_key = system::get_proof_of_stake();
    let amount: U512 = runtime::get_arg(0)
        .unwrap_or_revert_with(ApiError::MissingArgument)
        .unwrap_or_revert_with(ApiError::InvalidArgument);
    bond(contract_key, amount, account::get_main_purse());
    unbond(contract_key, Some(amount + 1));
}
