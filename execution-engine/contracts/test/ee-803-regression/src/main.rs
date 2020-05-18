#![no_std]
#![no_main]

extern crate alloc;

use alloc::{string::ToString, vec};

use alloc::string::String;
use contract::{
    contract_api::{runtime, system},
    unwrap_or_revert::UnwrapOrRevert,
};
use types::{ApiError, CLValue, NamedArg, RuntimeArgs, URef, U512};

const ARG_AMOUNT: &str = "amount";
const ARG_PURSE: &str = "purse";
const BOND: &str = "bond";
const UNBOND: &str = "unbond";
const ARG_ENTRY_POINT_NAME: &str = "method";

fn bond(bond_amount: U512, bonding_purse: URef) {
    let contract_hash = system::get_proof_of_stake();
    let runtime_args = {
        let args = vec![
            NamedArg::new(
                ARG_AMOUNT.to_string(),
                CLValue::from_t(bond_amount).unwrap_or_revert(),
            ),
            NamedArg::new(
                ARG_PURSE.to_string(),
                CLValue::from_t(bonding_purse).unwrap_or_revert(),
            ),
        ];
        RuntimeArgs::Named(args)
    };
    runtime::call_contract(contract_hash, BOND, runtime_args)
}

fn unbond(unbond_amount: Option<U512>) {
    let contract_hash = system::get_proof_of_stake();
    let runtime_args = {
        let args = vec![NamedArg::new(
            ARG_AMOUNT.to_string(),
            CLValue::from_t(unbond_amount).unwrap_or_revert(),
        )];
        RuntimeArgs::Named(args)
    };
    runtime::call_contract(contract_hash, UNBOND, runtime_args)
}

#[no_mangle]
pub extern "C" fn call() {
    let command: String = runtime::get_named_arg(ARG_ENTRY_POINT_NAME);
    match command.as_str() {
        BOND => {
            let rewards_purse: URef = runtime::get_named_arg(ARG_PURSE);
            let available_reward = runtime::get_named_arg(ARG_AMOUNT);
            // Attempt to bond using the rewards purse - should not be possible
            bond(available_reward, rewards_purse);
        }
        UNBOND => {
            unbond(None);
        }
        _ => runtime::revert(ApiError::InvalidArgument),
    }
}
