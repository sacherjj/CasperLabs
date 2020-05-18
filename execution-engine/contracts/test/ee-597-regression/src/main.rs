#![no_std]
#![no_main]

extern crate alloc;

use alloc::{string::ToString, vec};

use contract::{
    contract_api::{account, runtime, system},
    unwrap_or_revert::UnwrapOrRevert,
};
use types::{CLValue, ContractHash, NamedArg, RuntimeArgs, URef, U512};

const ARG_AMOUNT: &str = "amount";
const ARG_PURSE: &str = "purse";
const BOND: &str = "bond";

fn bond(contract_hash: ContractHash, bond_amount: U512, bonding_purse: URef) {
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
    runtime::call_contract::<()>(contract_hash, BOND, runtime_args);
}

#[no_mangle]
pub extern "C" fn call() {
    bond(
        system::get_proof_of_stake(),
        U512::from(0),
        account::get_main_purse(),
    );
}
