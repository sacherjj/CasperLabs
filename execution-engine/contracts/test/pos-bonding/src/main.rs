#![no_std]
#![no_main]

extern crate alloc;

use alloc::{
    string::{String, ToString},
    vec,
};

use contract::{
    contract_api::{account, runtime, system},
    unwrap_or_revert::UnwrapOrRevert,
};

use types::{
    account::PublicKey, ApiError, CLValue, ContractHash, NamedArg, RuntimeArgs, URef, U512,
};

const ARG_AMOUNT: &str = "amount";
const ARG_PURSE: &str = "purse";
const ARG_ENTRY_POINT: &str = "entry_point";
const ARG_BOND: &str = "bond";
const ARG_UNBOND: &str = "unbond";
const ARG_ACCOUNT_PK: &str = "account_public_key";
const TEST_BOND_FROM_MAIN_PURSE: &str = "bond-from-main-purse";
const TEST_SEED_NEW_ACCOUNT: &str = "seed_new_account";

#[repr(u16)]
enum Error {
    UnableToSeedAccount,
    UnknownCommand,
}

#[no_mangle]
pub extern "C" fn call() {
    let command: String = runtime::get_named_arg(ARG_ENTRY_POINT);

    match command.as_str() {
        ARG_BOND => bond(),
        ARG_UNBOND => unbond(),
        TEST_BOND_FROM_MAIN_PURSE => bond_from_main_purse(),
        TEST_SEED_NEW_ACCOUNT => seed_new_account(),
        _ => runtime::revert(ApiError::User(Error::UnknownCommand as u16)),
    }
}
fn bond() {
    let pos_contract_hash = system::get_proof_of_stake();
    // Creates new purse with desired amount based on main purse and sends funds
    let amount = runtime::get_named_arg(ARG_AMOUNT);
    let bonding_purse = system::create_purse();

    system::transfer_from_purse_to_purse(account::get_main_purse(), bonding_purse, amount)
        .unwrap_or_revert();

    bonding(pos_contract_hash, amount, bonding_purse);
}

fn bond_from_main_purse() {
    let pos_contract_hash = system::get_proof_of_stake();
    let amount = runtime::get_named_arg(ARG_AMOUNT);
    bonding(pos_contract_hash, amount, account::get_main_purse());
}

fn bonding(pos: ContractHash, bond_amount: U512, bonding_purse: URef) {
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
    runtime::call_contract(pos, ARG_BOND, runtime_args)
}

fn unbond() {
    let pos_contract_hash = system::get_proof_of_stake();
    let maybe_amount: Option<U512> = runtime::get_named_arg(ARG_AMOUNT);
    unbonding(pos_contract_hash, maybe_amount);
}

fn unbonding(pos: ContractHash, unbond_amount: Option<U512>) {
    let runtime_args = {
        let args = vec![NamedArg::new(
            ARG_AMOUNT.to_string(),
            CLValue::from_t(unbond_amount).unwrap_or_revert(),
        )];
        RuntimeArgs::Named(args)
    };
    runtime::call_contract(pos, ARG_UNBOND, runtime_args)
}

fn seed_new_account() {
    let source = account::get_main_purse();
    let target: PublicKey = runtime::get_named_arg(ARG_ACCOUNT_PK);
    let amount: U512 = runtime::get_named_arg(ARG_AMOUNT);
    system::transfer_from_purse_to_account(source, target, amount)
        .unwrap_or_revert_with(ApiError::User(Error::UnableToSeedAccount as u16));
}
