#![no_std]
#![no_main]

extern crate alloc;

use alloc::{string::ToString, vec};

use contract::{
    contract_api::{account, runtime, system},
    unwrap_or_revert::UnwrapOrRevert,
};
use types::{CLValue, NamedArg, RuntimeArgs, U512};

const BOND_METHOD_NAME: &str = "bond";

const ARG_AMOUNT: &str = "amount";
const ARG_PURSE: &str = "purse";

// Bonding contract.
//
// Accepts bonding amount (of type `u64`) as first argument.
// Issues bonding request to the PoS contract.
#[no_mangle]
pub extern "C" fn call() {
    // get bond amount arg
    let bond_amount: U512 = runtime::get_named_arg(ARG_AMOUNT);

    // provision bonding purse
    let bonding_purse = {
        let bonding_purse = system::create_purse();
        let source_purse = account::get_main_purse();
        // transfer amount to be bonded to bonding purse
        system::transfer_from_purse_to_purse(source_purse, bonding_purse, bond_amount)
            .unwrap_or_revert();
        bonding_purse
    };

    // bond
    {
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
        runtime::call_contract(contract_hash, BOND_METHOD_NAME, runtime_args)
    }
}
