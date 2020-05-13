#![no_std]
#![no_main]

extern crate alloc;

use alloc::string::String;

use contract::{
    contract_api::{runtime, system},
    unwrap_or_revert::UnwrapOrRevert,
};
use types::{ApiError, Key, URef, U512};

const POS_BOND: &str = "bond";
const POS_UNBOND: &str = "unbond";

const COMMAND_BOND: &str = "bond";
const COMMAND_UNBOND: &str = "unbond";

fn bond(contract_key: Key, amount: &U512, source: URef) {
    runtime::call_contract::<_, ()>(contract_key, POS_BOND, (*amount, source));
}

fn unbond(contract_key: Key, amount: Option<U512>) {
    runtime::call_contract::<_, ()>(contract_key, POS_UNBOND, (amount,));
}

#[no_mangle]
pub extern "C" fn call() {
    let command: String = runtime::get_arg(0)
        .unwrap_or_revert_with(ApiError::MissingArgument)
        .unwrap_or_revert_with(ApiError::InvalidArgument);
    let contract_key = system::get_proof_of_stake();
    if command == COMMAND_BOND {
        let rewards_purse: URef = runtime::get_arg(1)
            .unwrap_or_revert_with(ApiError::MissingArgument)
            .unwrap_or_revert_with(ApiError::InvalidArgument);
        let available_reward = runtime::get_arg(2)
            .unwrap_or_revert_with(ApiError::MissingArgument)
            .unwrap_or_revert_with(ApiError::InvalidArgument);
        // Attempt to bond using the rewards purse - should not be possible
        bond(contract_key, &available_reward, rewards_purse);
    } else if command == COMMAND_UNBOND {
        unbond(contract_key, None);
    }
}
